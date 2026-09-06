/**
 * Training event form behaviour.
 *
 * The CSP forbids inline <script>, so everything the form does lives here:
 *  - moving the end time with the start time, the way Google Calendar does
 *  - adding and removing style-breakdown rows, keeping their input names contiguous
 *  - showing the style total against the length of the session slot
 */
(function () {
    'use strict';

    const form = document.getElementById('trainingEventForm');
    if (!form) return;

    const dateInput = document.getElementById('date');
    const startInput = document.getElementById('startTime');
    const endInput = document.getElementById('endTime');
    const endDateInput = document.getElementById('endDate');
    const endDateRow = document.getElementById('endDateRow');
    const toggleEndDate = document.getElementById('toggleEndDate');
    const segmentRows = document.getElementById('segmentRows');
    const addSegment = document.getElementById('addSegment');
    const rowTemplate = document.getElementById('segmentRowTemplate');
    const segmentTotal = document.getElementById('segmentTotal');

    const DEFAULT_DURATION_MINUTES = 60;

    function toMinutes(value) {
        if (!value) return null;
        const parts = value.split(':');
        return parseInt(parts[0], 10) * 60 + parseInt(parts[1], 10);
    }

    function toTimeValue(minutes) {
        const wrapped = ((minutes % 1440) + 1440) % 1440;
        const h = String(Math.floor(wrapped / 60)).padStart(2, '0');
        const m = String(wrapped % 60).padStart(2, '0');
        return h + ':' + m;
    }

    /** Length of the session slot in minutes, spanning midnight or multiple days. */
    function slotMinutes() {
        const start = toMinutes(startInput && startInput.value);
        const end = toMinutes(endInput && endInput.value);
        if (start === null || end === null) return null;

        let days = 0;
        if (dateInput && dateInput.value && endDateInput && endDateInput.value) {
            const startDate = new Date(dateInput.value + 'T00:00');
            const endDate = new Date(endDateInput.value + 'T00:00');
            days = Math.round((endDate - startDate) / 86400000);
            if (!isFinite(days) || days < 0) days = 0;
        }
        return end - start + days * 1440;
    }

    // Keep the duration steady when the start moves, so shifting a session an hour later
    // does not silently make it an hour longer.
    let lastStart = startInput ? toMinutes(startInput.value) : null;

    if (startInput) {
        startInput.addEventListener('change', function () {
            const newStart = toMinutes(startInput.value);
            const end = toMinutes(endInput.value);
            if (newStart === null) return;

            if (lastStart !== null && end !== null) {
                const duration = end - lastStart;
                if (duration > 0) {
                    endInput.value = toTimeValue(newStart + duration);
                }
            } else if (end === null) {
                endInput.value = toTimeValue(newStart + DEFAULT_DURATION_MINUTES);
            }
            lastStart = newStart;
            updateTotal();
        });
    }

    if (endInput) {
        endInput.addEventListener('change', updateTotal);
    }

    if (toggleEndDate && endDateRow) {
        // Reveal the end date automatically when the event already spans days.
        if (endDateInput && endDateInput.value && dateInput && endDateInput.value !== dateInput.value) {
            endDateRow.classList.remove('hidden');
        }
        toggleEndDate.addEventListener('click', function () {
            endDateRow.classList.toggle('hidden');
            if (!endDateRow.classList.contains('hidden') && endDateInput && !endDateInput.value && dateInput) {
                endDateInput.value = dateInput.value;
            }
            updateTotal();
        });
    }

    if (endDateInput) {
        endDateInput.addEventListener('change', updateTotal);
    }

    /**
     * Spring binds list properties by index, so the names have to stay contiguous —
     * segments[0], segments[1], ... — or a removal in the middle drops later rows.
     */
    function reindexRows() {
        const rows = segmentRows.querySelectorAll('[data-segment-row]');
        rows.forEach(function (row, index) {
            row.querySelectorAll('select, input').forEach(function (field) {
                if (!field.name) return;
                field.name = field.name.replace(/segments\[[^\]]*\]/, 'segments[' + index + ']');
            });
        });
    }

    function updateTotal() {
        if (!segmentTotal) return;
        let total = 0;
        segmentRows.querySelectorAll('[data-segment-minutes]').forEach(function (input) {
            const value = parseInt(input.value, 10);
            if (!isNaN(value) && value > 0) total += value;
        });

        const slot = slotMinutes();
        if (total === 0) {
            segmentTotal.textContent = '';
            segmentTotal.classList.remove('text-error');
            return;
        }

        let label = total + ' min';
        if (slot !== null && slot > 0) {
            label += ' of ' + slot + ' min';
        }
        segmentTotal.textContent = label;
        segmentTotal.classList.toggle('text-error', slot !== null && total > slot);
    }

    if (addSegment && rowTemplate && segmentRows) {
        addSegment.addEventListener('click', function () {
            const index = segmentRows.querySelectorAll('[data-segment-row]').length;
            const fragment = rowTemplate.content.cloneNode(true);
            fragment.querySelectorAll('select, input').forEach(function (field) {
                if (field.name) field.name = field.name.replace('INDEX', index);
            });
            segmentRows.appendChild(fragment);
            updateTotal();
        });
    }

    if (segmentRows) {
        segmentRows.addEventListener('click', function (event) {
            const button = event.target.closest('[data-remove-segment]');
            if (!button) return;
            const row = button.closest('[data-segment-row]');
            if (row) row.remove();
            reindexRows();
            updateTotal();
        });

        segmentRows.addEventListener('input', function (event) {
            if (event.target.matches('[data-segment-minutes]')) updateTotal();
        });
    }

    // ── Repeats ──────────────────────────────────────────────────────────────
    const repeatSelect = document.getElementById('repeat');
    const repeatUntilRow = document.getElementById('repeatUntilRow');
    const repeatUntil = document.getElementById('repeatUntil');
    const repeatSummary = document.getElementById('repeatSummary');

    const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
    const MAX_OCCURRENCES = 52;

    function chosenWeekday() {
        if (!dateInput || !dateInput.value) return null;
        const d = new Date(dateInput.value + 'T00:00');
        return isNaN(d) ? null : WEEKDAYS[d.getDay()];
    }

    function refreshRepeat() {
        if (!repeatSelect || !repeatUntilRow) return;
        const repeating = repeatSelect.value === 'WEEKLY';
        repeatUntilRow.classList.toggle('hidden', !repeating);
        if (repeatUntil) repeatUntil.required = repeating;

        // Name the weekday explicitly so "Weekly on this weekday" is never ambiguous.
        const weekday = chosenWeekday();
        const weeklyOption = repeatSelect.querySelector('option[value="WEEKLY"]');
        if (weeklyOption) {
            weeklyOption.textContent = weekday ? 'Weekly on ' + weekday : 'Weekly on this weekday';
        }
        if (!repeating) return;

        if (repeatUntil && !repeatUntil.value && dateInput && dateInput.value) {
            const start = new Date(dateInput.value + 'T00:00');
            start.setDate(start.getDate() + 7 * 11);
            repeatUntil.value = start.toISOString().slice(0, 10);
        }
        updateRepeatSummary();
    }

    function updateRepeatSummary() {
        if (!repeatSummary || !dateInput || !dateInput.value || !repeatUntil || !repeatUntil.value) return;
        const start = new Date(dateInput.value + 'T00:00');
        const end = new Date(repeatUntil.value + 'T00:00');
        const weeks = Math.floor((end - start) / (7 * 86400000)) + 1;

        if (!isFinite(weeks) || weeks < 1) {
            repeatSummary.textContent = 'The end date must not be before the first session.';
            repeatSummary.classList.add('text-error');
            return;
        }
        repeatSummary.textContent = weeks > MAX_OCCURRENCES
            ? 'That is ' + weeks + ' sessions; the limit is ' + MAX_OCCURRENCES + '.'
            : 'Creates ' + weeks + ' session' + (weeks === 1 ? '' : 's') + ' up front.';
        repeatSummary.classList.toggle('text-error', weeks > MAX_OCCURRENCES);
    }

    if (repeatSelect) repeatSelect.addEventListener('change', refreshRepeat);
    if (repeatUntil) repeatUntil.addEventListener('change', updateRepeatSummary);
    if (dateInput) dateInput.addEventListener('change', refreshRepeat);

    refreshRepeat();
    updateTotal();
})();
