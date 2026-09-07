/**
 * Draws the two charts on the training statistics page.
 *
 * Both datasets arrive as JSON on the canvas elements themselves, colours included, so
 * this file holds no data and no palette -- the colours are Noble Harmony tokens resolved
 * server-side in TrainingEventPalette, which is the only place a training concept becomes
 * a hex value.
 */
(function () {
    function readSlices(canvas) {
        try {
            return JSON.parse(canvas.dataset.slices || '[]');
        } catch (error) {
            console.error('[training-stats] could not read chart data', error);
            return [];
        }
    }

    /**
     * Chart.js hands the callback an index, not our slice. Closing over the array keeps
     * the pre-formatted duration reachable without hanging a custom property off Chart's
     * own data object, which it makes no promise to preserve.
     */
    function tooltipLabelFor(slices) {
        return function (context) {
            var slice = slices[context.dataIndex];
            return ' ' + slice.label + ': ' + slice.durationLabel;
        };
    }

    function drawCategoryChart(canvas) {
        var slices = readSlices(canvas);
        if (slices.length === 0) return;

        new Chart(canvas, {
            type: 'doughnut',
            data: {
                labels: slices.map(function (slice) { return slice.label; }),
                datasets: [{
                    data: slices.map(function (slice) { return slice.minutes; }),
                    backgroundColor: slices.map(function (slice) { return slice.color; }),
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '62%',
                plugins: {
                    // The template renders the legend as a list, which screen readers can
                    // reach and which survives JavaScript being switched off.
                    legend: { display: false },
                    tooltip: { callbacks: { label: tooltipLabelFor(slices) } }
                }
            }
        });
    }

    function drawEventTypeChart(canvas) {
        var slices = readSlices(canvas);
        if (slices.length === 0) return;

        new Chart(canvas, {
            type: 'bar',
            data: {
                labels: slices.map(function (slice) { return slice.label; }),
                datasets: [{
                    data: slices.map(function (slice) { return slice.minutes; }),
                    backgroundColor: slices.map(function (slice) { return slice.color; }),
                    borderRadius: 6
                }]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: {
                        ticks: {
                            // Minutes are the unit the data is in; hours are the unit a
                            // person reads an axis in.
                            callback: function (value) { return Math.round(value / 60) + 'h'; }
                        },
                        grid: { color: '#e5e2e1' }
                    },
                    y: { grid: { display: false } }
                },
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: tooltipLabelFor(slices) } }
                }
            }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var categoryCanvas = document.getElementById('category-chart');
        if (categoryCanvas) drawCategoryChart(categoryCanvas);

        var eventTypeCanvas = document.getElementById('event-type-chart');
        if (eventTypeCanvas) drawEventTypeChart(eventTypeCanvas);
    });
})();
