/**
 * The FullCalendar training view.
 *
 * Inline scripts are impossible under this CSP, so all wiring lives here: the JSON feed, the
 * chip renderer, click-to-create, and drag/resize writing through to Google Calendar.
 *
 * The page is two different things either side of 768px:
 *
 *   Pointer  — month, week and list views. Dragging a slot opens an anchored quick-create
 *              card; dragging or resizing a session reschedules it.
 *   Touch    — a month grid of load dots with the selected day's sessions listed underneath,
 *              and a floating + that creates on that day. Tap reads, + creates: nothing is
 *              overloaded, so no long-press is needed, and drag-to-reschedule stays off where
 *              it would only ever fire by accident.
 *
 * Everything responsive is driven by the media query below and re-applied on `change`, so
 * rotating a phone switches the page over instead of leaving whichever layout happened to
 * be right when the script first ran.
 */
(function () {
    'use strict';

    const host = document.getElementById('trainingCalendar');
    if (!host || typeof FullCalendar === 'undefined') return;

    const popover = document.getElementById('quickCreatePopover');
    const sheet = document.getElementById('quickCreateSheet');
    const sheetBody = document.getElementById('quickCreateSheetBody');
    const backdrop = document.getElementById('quickCreateBackdrop');
    const fab = document.getElementById('quickCreateFab');
    const errorBox = document.getElementById('calendarError');

    const agendaTitle = document.getElementById('dayAgendaTitle');
    const agendaTotal = document.getElementById('dayAgendaTotal');
    const agendaList = document.getElementById('dayAgendaList');
    const agendaTemplate = document.getElementById('dayAgendaItemTemplate');

    const narrow = window.matchMedia('(max-width: 767px)');

    /** The day whose sessions the agenda panel is showing, as 'YYYY-MM-DD'. */
    let selectedKey = dateKey(new Date());
    let repaintHandle = null;

    /** Evening default for the floating +, matching the server's own quick-create default. */
    const DEFAULT_HOUR = 18;

    // ── Formatting ──────────────────────────────────────────────────────────

    function pad(n) {
        return String(n).padStart(2, '0');
    }

    /**
     * Format as a local ISO string with no offset. The server stores LocalDateTime and the
     * calendar runs on timeZone 'local'; letting toISOString() convert to UTC here would
     * shift every session by the zone offset.
     */
    function toLocalIso(date) {
        return dateKey(date) + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':00';
    }

    function dateKey(date) {
        return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
    }

    function hhmm(date) {
        return pad(date.getHours()) + ':' + pad(date.getMinutes());
    }

    /** "9h 30m", "45m", "3h" — whichever parts are non-zero. */
    function durationLabel(minutes) {
        const hours = Math.floor(minutes / 60);
        const rest = minutes % 60;
        if (!hours) return rest + 'm';
        if (!rest) return hours + 'h';
        return hours + 'h ' + rest + 'm';
    }

    function minutesBetween(start, end) {
        return Math.max(0, Math.round((end - start) / 60000));
    }

    /** CSRF for fetch, the same lookup choreography-builder.js uses for its reorder POSTs. */
    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        if (token && header) headers[header.getAttribute('content')] = token.getAttribute('content');
        return headers;
    }

    function showError(message) {
        if (!errorBox) return;
        errorBox.textContent = message;
        errorBox.classList.remove('hidden');
    }

    function clearError() {
        if (errorBox) errorBox.classList.add('hidden');
    }

    // ── Chips ───────────────────────────────────────────────────────────────

    /**
     * A session chip: a solid stripe in the status colour, that colour at 10% behind it, and
     * the detail that fits. The feed sends the tint as backgroundColor and the solid colour
     * as borderColor; the stylesheet clears FullCalendar's own fill so only this shows.
     *
     * List views keep FullCalendar's own row rendering, which the stylesheet themes instead.
     */
    function renderChip(arg) {
        if (arg.view.type.indexOf('list') === 0) return true;

        const props = arg.event.extendedProps || {};
        const chip = document.createElement('div');
        chip.className = 'tc-chip' + (props.status === 'cancelled' ? ' tc-chip-struck' : '');
        chip.style.backgroundColor = arg.event.backgroundColor || 'transparent';
        chip.style.borderLeftColor = arg.event.borderColor || 'transparent';

        const time = document.createElement('span');
        time.className = 'tc-chip-time';
        time.textContent = arg.timeText || (arg.event.start ? hhmm(arg.event.start) : '');
        if (props.repeating) {
            const repeat = document.createElement('span');
            repeat.className = 'material-symbols-outlined align-middle text-[12px] ml-1';
            repeat.textContent = 'repeat';
            time.appendChild(repeat);
        }
        chip.appendChild(time);

        const title = document.createElement('span');
        title.className = 'tc-chip-title';
        title.textContent = arg.event.title;
        chip.appendChild(title);

        // The style breakdown only earns its line where there is vertical room for it; a
        // month cell has to hold several chips.
        if (props.styles && props.styles.length && arg.view.type.indexOf('timeGrid') === 0) {
            const styles = document.createElement('span');
            styles.className = 'tc-chip-styles';
            styles.textContent = props.styles.join(' · ');
            chip.appendChild(styles);
        }

        return { domNodes: [chip] };
    }

    // ── Calendar ────────────────────────────────────────────────────────────

    function toolbarFor(isNarrow) {
        return isNarrow
            // No view switcher on a phone: week and list views are unreadable at 360px, and
            // the month grid plus the agenda panel below it already covers both jobs.
            ? { left: 'prev,next', center: 'title', right: 'today' }
            : { left: 'prev,next today', center: 'title', right: 'dayGridMonth,timeGridWeek,listWeek' };
    }

    const calendar = new FullCalendar.Calendar(host, {
        initialView: 'dayGridMonth',
        timeZone: 'local',
        firstDay: 1,
        height: 'auto',
        nowIndicator: true,
        selectable: !narrow.matches,
        editable: !narrow.matches,
        eventDisplay: narrow.matches ? 'none' : 'auto',
        headerToolbar: toolbarFor(narrow.matches),
        titleFormat: { year: 'numeric', month: 'long' },
        eventTimeFormat: { hour: '2-digit', minute: '2-digit', hour12: false },
        // Emptied on purpose: the stylesheet draws real chevrons with Material Symbols, which
        // font-src allows, where FullCalendar's own icon font is a blocked data: URI.
        buttonIcons: false,
        buttonText: { today: 'Today', month: 'Month', week: 'Week', list: 'List', prev: '', next: '' },
        views: {
            dayGridMonth: { dayMaxEvents: 2 }
        },
        eventContent: renderChip,

        events: function (fetchInfo, success, failure) {
            const url = '/api/training-events/calendar?start=' +
                encodeURIComponent(fetchInfo.startStr) + '&end=' + encodeURIComponent(fetchInfo.endStr);
            fetch(url, { headers: { 'Accept': 'application/json' } })
                .then(function (r) {
                    if (!r.ok) throw new Error('Could not load sessions');
                    return r.json();
                })
                .then(success)
                .catch(function (e) {
                    showError(e.message);
                    failure(e);
                });
        },

        // Let the browser follow the event's own url instead of FullCalendar's handling,
        // so a session opens the app's detail page.
        eventClick: function (info) {
            hideQuickCreate();
            if (info.event.url) {
                info.jsEvent.preventDefault();
                window.location.href = info.event.url;
            }
        },

        // Touch: tapping a day reads it. Creation is the floating +, so a mis-tap costs
        // nothing and the create form never appears without being asked for.
        dateClick: function (info) {
            if (!narrow.matches) return;
            clearError();
            selectedKey = info.dateStr.slice(0, 10);
            paint();
        },

        // Pointer: dragging a range creates on it, as before.
        select: function (info) {
            if (narrow.matches) return;
            clearError();
            openQuickCreate(info.start, info.end, info.jsEvent);
        },

        eventDrop: persistMove,
        eventResize: persistMove,

        datesSet: function (info) {
            if (narrow.matches) keepSelectionInView(info.view);
            schedulePaint();
        },

        eventsSet: schedulePaint
    });

    /** Writes a drag or resize through the reschedule endpoint, reverting if it is refused. */
    function persistMove(info) {
        clearError();
        const end = info.event.end || new Date(info.event.start.getTime() + 3600000);
        const body = 'start=' + encodeURIComponent(toLocalIso(info.event.start)) +
            '&end=' + encodeURIComponent(toLocalIso(end));

        fetch('/training-events/' + info.event.id + '/reschedule', {
            method: 'POST',
            headers: csrfHeaders(),
            body: body
        }).then(function (response) {
            if (response.ok) return null;
            return response.json().then(function (data) {
                throw new Error(data.error || 'Could not move this session');
            });
        }).catch(function (e) {
            showError(e.message);
            info.revert();
        });
    }

    // ── Touch layout: day dots and the selected-day agenda ──────────────────

    /** Navigating to another month would otherwise leave the agenda on a day that is gone. */
    function keepSelectionInView(view) {
        const first = dateKey(view.currentStart);
        const past = dateKey(view.currentEnd);
        if (selectedKey >= first && selectedKey < past) return;
        const today = dateKey(new Date());
        selectedKey = (today >= first && today < past) ? today : first;
    }

    function eventsByDay() {
        const byDay = {};
        calendar.getEvents().forEach(function (event) {
            if (!event.start) return;
            const key = dateKey(event.start);
            (byDay[key] = byDay[key] || []).push(event);
        });
        Object.keys(byDay).forEach(function (key) {
            byDay[key].sort(function (a, b) { return a.start - b.start; });
        });
        return byDay;
    }

    /** FullCalendar rebuilds its cells on every render, so repaint after one rather than during. */
    function schedulePaint() {
        if (repaintHandle) return;
        repaintHandle = window.requestAnimationFrame(function () {
            repaintHandle = null;
            paint();
        });
    }

    function paint() {
        clearDayDecorations();
        if (!narrow.matches) return;

        const byDay = eventsByDay();

        host.querySelectorAll('.fc-daygrid-day').forEach(function (cell) {
            const key = cell.getAttribute('data-date');
            if (key === selectedKey) cell.setAttribute('data-tc-selected', '');

            const events = byDay[key];
            const slot = cell.querySelector('.fc-daygrid-day-events');
            if (!events || !slot) return;

            const dots = document.createElement('div');
            dots.className = 'tc-daydots';
            events.slice(0, 4).forEach(function (event) {
                const dot = document.createElement('span');
                dot.className = 'tc-daydot';
                dot.style.backgroundColor = event.borderColor || 'currentColor';
                dots.appendChild(dot);
            });
            slot.appendChild(dots);
        });

        renderDayAgenda(byDay[selectedKey] || []);
    }

    function clearDayDecorations() {
        host.querySelectorAll('.tc-daydots').forEach(function (node) { node.remove(); });
        host.querySelectorAll('[data-tc-selected]').forEach(function (node) {
            node.removeAttribute('data-tc-selected');
        });
    }

    function renderDayAgenda(events) {
        if (!agendaList || !agendaTemplate) return;

        if (agendaTitle) {
            agendaTitle.textContent = new Date(selectedKey + 'T00:00')
                .toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
        }
        agendaList.textContent = '';

        if (!events.length) {
            if (agendaTotal) agendaTotal.textContent = '';
            const empty = document.createElement('p');
            empty.className = 'tc-agenda-empty';
            empty.textContent = 'Nothing scheduled. Tap + to add a session.';
            agendaList.appendChild(empty);
            return;
        }

        if (agendaTotal) {
            const total = events.reduce(function (sum, event) {
                return sum + (event.end ? minutesBetween(event.start, event.end) : 0);
            }, 0);
            agendaTotal.textContent = events.length + (events.length === 1 ? ' session · ' : ' sessions · ') +
                durationLabel(total);
        }

        events.forEach(function (event) {
            agendaList.appendChild(agendaRow(event));
        });
    }

    function agendaRow(event) {
        const props = event.extendedProps || {};
        const row = agendaTemplate.content.firstElementChild.cloneNode(true);

        row.setAttribute('href', event.url || '#');
        row.style.backgroundColor = event.backgroundColor || '';
        row.style.borderLeftColor = event.borderColor || '';

        row.querySelector('[data-agenda-start]').textContent = hhmm(event.start);
        row.querySelector('[data-agenda-end]').textContent = event.end ? hhmm(event.end) : '';

        const title = row.querySelector('[data-agenda-title]');
        title.textContent = event.title;
        if (props.status === 'cancelled') title.classList.add('line-through', 'opacity-70');

        const meta = row.querySelector('[data-agenda-meta]');
        const parts = [];
        if (props.statusLabel) parts.push(props.statusLabel);
        if (event.end) parts.push(durationLabel(minutesBetween(event.start, event.end)));
        meta.textContent = parts.join(' · ');

        const styles = row.querySelector('[data-agenda-styles]');
        if (props.styles && props.styles.length) {
            styles.textContent = props.styles.join(' · ');
        } else {
            styles.remove();
        }

        return row;
    }

    // ── Quick create ────────────────────────────────────────────────────────

    /** The sheet on touch, the anchored card on a pointer. Both hold the same fragment. */
    function quickCreateBody() {
        return narrow.matches ? sheetBody : popover;
    }

    function hideQuickCreate() {
        if (popover) {
            popover.classList.add('hidden');
            popover.textContent = '';
        }
        if (sheet) sheet.classList.add('hidden');
        if (sheetBody) sheetBody.textContent = '';
        if (backdrop) backdrop.classList.add('hidden');
        document.body.classList.remove('overflow-hidden');
        calendar.unselect();
    }

    function openQuickCreate(start, end, jsEvent) {
        const body = quickCreateBody();
        if (!body) return;

        const url = '/training-events/quick-create?start=' + encodeURIComponent(toLocalIso(start)) +
            '&end=' + encodeURIComponent(toLocalIso(end));

        fetch(url, { headers: { 'Accept': 'text/html' } })
            .then(function (r) {
                if (!r.ok) throw new Error('Could not open the quick create form');
                return r.text();
            })
            .then(function (html) {
                body.innerHTML = html;

                if (narrow.matches) {
                    backdrop.classList.remove('hidden');
                    sheet.classList.remove('hidden');
                    sheet.scrollTop = 0;
                    // The page behind a sheet should not scroll away underneath it.
                    document.body.classList.add('overflow-hidden');
                } else {
                    popover.classList.remove('hidden');
                    positionPopover(jsEvent);
                }

                wireQuickCreate(body);
                const title = body.querySelector('input[type="text"]');
                if (title) title.focus();
            })
            .catch(function (e) { showError(e.message); });
    }

    /**
     * Place the card next to the click, keeping it on screen. Clamping to the calendar
     * container is not enough — the month grid is taller than the viewport, so a click low
     * down would put the card below the fold. Flip it above the cursor in that case.
     */
    function positionPopover(jsEvent) {
        const container = popover.offsetParent;
        if (!container || !jsEvent) return;

        const bounds = container.getBoundingClientRect();
        const width = popover.offsetWidth;
        const height = popover.offsetHeight;
        const margin = 8;

        let clientLeft = jsEvent.clientX + margin;
        let clientTop = jsEvent.clientY + margin;

        if (clientLeft + width > window.innerWidth - margin) {
            clientLeft = Math.max(margin, window.innerWidth - width - margin);
        }
        if (clientTop + height > window.innerHeight - margin) {
            clientTop = Math.max(margin, jsEvent.clientY - height - margin);
        }

        // Back into coordinates relative to the positioned ancestor.
        popover.style.left = (clientLeft - bounds.left) + 'px';
        popover.style.top = (clientTop - bounds.top) + 'px';
    }

    function wireQuickCreate(root) {
        const close = root.querySelector('[data-close-quick-create]');
        if (close) close.addEventListener('click', hideQuickCreate);

        const repeat = root.querySelector('select[name="repeat"]');
        const untilRow = root.querySelector('#quickRepeatUntilRow');
        const until = untilRow ? untilRow.querySelector('input[type="date"]') : null;
        const date = root.querySelector('input[name="date"]');

        if (repeat && untilRow) {
            repeat.addEventListener('change', function () {
                const repeating = repeat.value === 'WEEKLY';
                untilRow.classList.toggle('hidden', !repeating);
                if (until) until.required = repeating;
                if (repeating && until && !until.value && date && date.value) {
                    const d = new Date(date.value + 'T00:00');
                    d.setDate(d.getDate() + 7 * 11);
                    until.value = d.toISOString().slice(0, 10);
                }
            });
        }
    }

    if (fab) {
        fab.addEventListener('click', function () {
            clearError();
            const start = new Date(selectedKey + 'T' + pad(DEFAULT_HOUR) + ':00:00');
            const end = new Date(start.getTime() + 3600000);
            openQuickCreate(start, end, null);
        });
    }

    if (backdrop) backdrop.addEventListener('click', hideQuickCreate);

    document.addEventListener('click', function (event) {
        if (!popover || popover.classList.contains('hidden')) return;
        if (popover.contains(event.target) || host.contains(event.target)) return;
        hideQuickCreate();
    });

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') hideQuickCreate();
    });

    // ── Breakpoint ──────────────────────────────────────────────────────────

    function applyBreakpoint() {
        const isNarrow = narrow.matches;
        calendar.batchRendering(function () {
            calendar.setOption('headerToolbar', toolbarFor(isNarrow));
            calendar.setOption('selectable', !isNarrow);
            calendar.setOption('editable', !isNarrow);
            calendar.setOption('eventDisplay', isNarrow ? 'none' : 'auto');
            if (isNarrow && calendar.view.type !== 'dayGridMonth') {
                calendar.changeView('dayGridMonth');
            }
        });
        schedulePaint();
    }

    function onBreakpointChange() {
        hideQuickCreate();
        applyBreakpoint();
    }

    if (narrow.addEventListener) {
        narrow.addEventListener('change', onBreakpointChange);
    } else if (narrow.addListener) {
        narrow.addListener(onBreakpointChange);
    }

    calendar.render();
    schedulePaint();
})();
