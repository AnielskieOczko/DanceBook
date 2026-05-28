(function() {
    function initSortable() {
        if (typeof Sortable === 'undefined') {
            setTimeout(initSortable, 50);
            return;
        }
        const el = document.getElementById('sequence-entries');
        if (!el) return;

        // Destroy existing instance to prevent duplicates
        if (el.sortableInstance) {
            el.sortableInstance.destroy();
        }

        const choreoId = document.getElementById('choreoIdInput')?.value;
        if (!choreoId) return;

        // Initialize SortableJS
        el.sortableInstance = new Sortable(el, {
            handle: '.cursor-grab',
            animation: 150,
            ghostClass: 'sortable-ghost',
            dragClass: 'sortable-drag',
            onEnd: function() {
                // Get ordered list of item IDs
                const ids = Array.from(el.children).map(child => child.getAttribute('data-id')).filter(Boolean);
                
                // Get CSRF headers
                const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
                const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
                const headers = {};
                
                if (csrfHeader && csrfToken) {
                    headers[csrfHeader] = csrfToken;
                }

                // POST reorder request using htmx.ajax
                htmx.ajax('POST', `/choreographies/${choreoId}/entries/reorder`, {
                    target: '#sequence-timeline-container',
                    values: { entryIds: ids.join(',') },
                    headers: headers
                });
            }
        });
    }

    // Initialize on page load
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initSortable);
    } else {
        initSortable();
    }

    // Re-initialize on HTMX content settlements (handles timeline swapping)
    document.addEventListener('htmx:afterSettle', initSortable);
})();
