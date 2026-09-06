/**
 * The FullCalendar training view.
 *
 * Inline scripts are impossible under this CSP, so all wiring lives here: the JSON feed,
 * click-to-create with a popover, and drag/resize writing through to Google Calendar.
 */
(function () {
    'use strict';

    const host = document.getElementById('trainingCalendar');
    if (!host || typeof FullCalendar === 'undefined') return;

    const popover = document.getElementById('quickCreatePopover');
    const errorBox = document.getElementById('calendarError');

    /** CSRF for fetch, the same lookup choreography-builder.js uses for its reorder POSTs. */
    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        if (token && header) headers[header.getAttribute('content')] = token.getAttribute('content');
        return headers;
    }

    /**
     * Format as a local ISO string with no offset. The server stores LocalDateTime and the
     * calendar runs on timeZone 'local'; letting toISOString() convert to UTC here would
     * shift every session by the zone offset.
     */
    function toLocalIso(date) {
        const pad = function (n) { return String(n).padStart(2, '0'); };
        return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) +
            'T' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':00';
    }

    function showError(message) {
        if (!errorBox) return;
        errorBox.textContent = message;
        errorBox.classList.remove('hidden');
    }

    function clearError() {
        if (errorBox) errorBox.classList.add('hidden');
    }

    function hidePopover() {
        if (popover) {
            popover.classList.add('hidden');
            popover.innerHTML = '';
        }
    }

    const isNarrow = window.matchMedia('(max-width: 768px)').matches;

    const calendar = new FullCalendar.Calendar(host, {
        initialView: isNarrow ? 'listWeek' : 'dayGridMonth',
        timeZone: 'local',
        firstDay: 1,
        height: 'auto',
        nowIndicator: true,
        selectable: true,
        editable: true,
        // One row of controls does not fit a phone: the title ends up wrapping across
        // three lines. Drop the view switcher to its own row there instead.
        headerToolbar: isNarrow
            ? { left: 'prev,next', center: 'title', right: 'today' }
            : { left: 'prev,next today', center: 'title', right: 'dayGridMonth,timeGridWeek,listWeek' },
        footerToolbar: isNarrow
            ? { center: 'dayGridMonth,timeGridWeek,listWeek' }
            : false,
        titleFormat: isNarrow
            ? { month: 'short', day: 'numeric' }
            : { year: 'numeric', month: 'long' },
        // FullCalendar's own chevrons come from an icon font embedded as a data: URI, which
        // font-src blocks. Use text arrows in the app's font rather than widening the CSP
        // for decoration -- otherwise prev/next render as empty boxes.
        buttonIcons: false,
        buttonText: {
            today: 'Today',
            month: 'Month',
            week: 'Week',
            list: 'List',
            prev: '\u2190',
            next: '\u2192'
        },

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
            hidePopover();
            if (info.event.url) {
                info.jsEvent.preventDefault();
                window.location.href = info.event.url;
            }
        },

        select: function (info) {
            clearError();
            openQuickCreate(info);
        },

        eventDrop: function (info) {
            persistMove(info);
        },

        eventResize: function (info) {
            persistMove(info);
        }
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

    function openQuickCreate(info) {
        if (!popover) return;
        const url = '/training-events/quick-create?start=' + encodeURIComponent(toLocalIso(info.start)) +
            '&end=' + encodeURIComponent(toLocalIso(info.end));

        fetch(url, { headers: { 'Accept': 'text/html' } })
            .then(function (r) {
                if (!r.ok) throw new Error('Could not open the quick create form');
                return r.text();
            })
            .then(function (html) {
                popover.innerHTML = html;
                popover.classList.remove('hidden');
                positionPopover(info.jsEvent);
                wireQuickCreate();
                const title = popover.querySelector('input[type="text"]');
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

    function wireQuickCreate() {
        const close = popover.querySelector('[data-close-quick-create]');
        if (close) close.addEventListener('click', function () {
            hidePopover();
            calendar.unselect();
        });

        const repeat = popover.querySelector('select[name="repeat"]');
        const untilRow = popover.querySelector('#quickRepeatUntilRow');
        const until = untilRow ? untilRow.querySelector('input[type="date"]') : null;
        const date = popover.querySelector('input[name="date"]');

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

    document.addEventListener('click', function (event) {
        if (!popover || popover.classList.contains('hidden')) return;
        if (popover.contains(event.target) || host.contains(event.target)) return;
        hidePopover();
    });

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') hidePopover();
    });

    calendar.render();
})();
