document.addEventListener('htmx:configRequest', function(event) {
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (tokenMeta && headerMeta) {
        event.detail.headers[headerMeta.content] = tokenMeta.content;
    }
});

document.addEventListener('htmx:afterRequest', function(event) {
    const target = event.target;
    if (target && (target.classList.contains('js-save-inline-figure') || target.closest('.js-save-inline-figure'))) {
        if (event.detail.successful) {
            setTimeout(() => {
                const errorElement = document.querySelector('#figureSelectFragment .text-error');
                if (!errorElement) {
                    const nameInput = document.getElementById('newFigureName');
                    const classInput = document.getElementById('newFigureClass');
                    const timingInput = document.getElementById('newFigureAltTiming');
                    const form = document.getElementById('inlineNewFigureForm');
                    if (nameInput) nameInput.value = '';
                    if (classInput) classInput.value = '';
                    if (timingInput) timingInput.value = '';
                    if (form) form.classList.add('hidden');
                }
            }, 50);
        }
    }
});

/**
 * Generic confirm-before-submit handler.
 * Usage: <form data-confirm="Are you sure?"> or <button data-confirm="Delete?">
 * Replaces inline onclick="return confirm(...)" which CSP blocks.
 */
document.addEventListener('submit', function(event) {
    const form = event.target;
    const message = form.getAttribute('data-confirm');
    if (message && !confirm(message)) {
        event.preventDefault();
    }
});

/**
 * Generic auto-submit on change handler.
 * Usage: <select class="js-filter-select">
 */
document.addEventListener('change', function(event) {
    const select = event.target.closest('.js-filter-select');
    if (select) {
        select.form.submit();
    }
});

/**
 * Global click handler for delegated events.
 */
document.addEventListener('click', function(event) {
    // 1. Expandable card handler
    const expandBtn = event.target.closest('.js-expand-btn');
    if (expandBtn) {
        event.preventDefault();
        event.stopPropagation();
        
        const article = expandBtn.closest('article');
        if (!article) return;
        
        const content = article.querySelector('.expandable-content');
        if (!content) return;
        
        const icon = expandBtn.querySelector('.icon-expand');
        const isExpanded = content.classList.contains('max-h-[1000px]');
        
        if (isExpanded) {
            content.classList.remove('max-h-[1000px]');
            content.classList.add('max-h-0');
            if (icon) icon.classList.remove('rotate-180');
        } else {
            content.classList.remove('max-h-0');
            content.classList.add('max-h-[1000px]');
            if (icon) icon.classList.add('rotate-180');
        }
        return;
    }

    // 2. Filter toggle handler
    const filterBtn = event.target.closest('.js-filter-toggle');
    if (filterBtn) {
        event.preventDefault();
        event.stopPropagation();
        
        const container = document.getElementById('categoryFiltersContainer');
        if (!container) return;
        
        const isHidden = container.classList.contains('max-h-0');
        
        if (isHidden) {
            container.classList.remove('max-h-0', 'opacity-0', 'pointer-events-none', 'mt-0');
            container.classList.add('max-h-[500px]', 'opacity-100', 'mt-4');
        } else {
            container.classList.add('max-h-0', 'opacity-0', 'pointer-events-none', 'mt-0');
            container.classList.remove('max-h-[500px]', 'opacity-100', 'mt-4');
        }
        return;
    }

    // 3. 3-dot menu toggle handler
    const menuBtn = event.target.closest('.js-menu-btn');
    if (menuBtn) {
        event.preventDefault();
        event.stopPropagation();
        
        const dropdown = menuBtn.nextElementSibling;
        if (!dropdown) return;
        
        const isHidden = dropdown.classList.contains('hidden');
        
        // Close all other menus first
        document.querySelectorAll('.js-menu-dropdown').forEach(d => {
            if (d !== dropdown) d.classList.add('hidden');
        });
        
        // Toggle current menu
        if (isHidden) {
            dropdown.classList.remove('hidden');
        } else {
            dropdown.classList.add('hidden');
        }
        return;
    }

    // 5. Delete list modal open
    const openDeleteModalBtn = event.target.closest('.js-open-delete-modal');
    if (openDeleteModalBtn) {
        event.preventDefault();
        const modal = document.getElementById('deleteConfirmModal');
        if (modal) modal.style.display = 'flex';
        return;
    }

    // 6. Delete list modal close
    const closeDeleteModalBtn = event.target.closest('.js-close-delete-modal');
    if (closeDeleteModalBtn) {
        event.preventDefault();
        const modal = document.getElementById('deleteConfirmModal');
        if (modal) modal.style.display = 'none';
        return;
    }

    // 7. Notification bell toggle
    const bellBtn = event.target.closest('#notification-bell');
    if (bellBtn) {
        event.preventDefault();
        event.stopPropagation();
        const dropdown = document.getElementById('notification-dropdown');
        if (dropdown) {
            dropdown.classList.toggle('hidden');
        }
        return;
    }

    // 8. Inline figure form toggle handler
    const toggleFigureBtn = event.target.closest('.js-toggle-inline-figure');
    if (toggleFigureBtn) {
        event.preventDefault();
        event.stopPropagation();
        const form = document.getElementById('inlineNewFigureForm');
        if (form) {
            form.classList.toggle('hidden');
            if (!form.classList.contains('hidden')) {
                const nameInput = document.getElementById('newFigureName');
                if (nameInput) nameInput.focus();
            }
        }
        return;
    }

    // 9. Close notification dropdown on mark all read click
    const closeNotifDropdownBtn = event.target.closest('.js-close-notification-dropdown');
    if (closeNotifDropdownBtn) {
        const dropdown = document.getElementById('notification-dropdown');
        if (dropdown) {
            dropdown.classList.add('hidden');
        }
    }

    // 10. Edit figure in sequence handler
    const editFigureBtn = event.target.closest('.js-edit-figure-btn');
    if (editFigureBtn) {
        event.preventDefault();
        const id = editFigureBtn.getAttribute('data-id');
        const danceFigureId = editFigureBtn.getAttribute('data-dance-figure-id');
        const startTime = editFigureBtn.getAttribute('data-start-time');
        const endTime = editFigureBtn.getAttribute('data-end-time');

        const idInput = document.getElementById('editFigureId');
        const selectEl = document.getElementById('danceFigureId');
        const startInput = document.getElementById('startTime');
        const endInput = document.getElementById('endTime');
        const cancelBtn = document.getElementById('cancelEditFigureBtn');
        const submitBtn = document.getElementById('submitFigureBtn');
        const headerText = document.getElementById('figureFormHeaderText');
        const headerIcon = document.getElementById('figureFormHeaderIcon');
        const formContainer = document.getElementById('figureFormContainer');

        if (idInput) idInput.value = id;
        if (selectEl) selectEl.value = danceFigureId;
        if (startInput) startInput.value = startTime;
        if (endInput) endInput.value = endTime;

        if (headerText) headerText.textContent = "Edit Figure in Sequence";
        if (headerIcon) headerIcon.textContent = "edit";
        if (submitBtn) submitBtn.textContent = "Update Figure";
        if (cancelBtn) cancelBtn.classList.remove('hidden');

        if (formContainer) {
            formContainer.scrollIntoView({ behavior: 'smooth' });
        }
        return;
    }

    // 11. Cancel edit figure in sequence handler
    const cancelEditBtn = event.target.closest('#cancelEditFigureBtn');
    if (cancelEditBtn) {
        event.preventDefault();
        const idInput = document.getElementById('editFigureId');
        const selectEl = document.getElementById('danceFigureId');
        const startInput = document.getElementById('startTime');
        const endInput = document.getElementById('endTime');
        const cancelBtn = document.getElementById('cancelEditFigureBtn');
        const submitBtn = document.getElementById('submitFigureBtn');
        const headerText = document.getElementById('figureFormHeaderText');
        const headerIcon = document.getElementById('figureFormHeaderIcon');

        if (idInput) idInput.value = '';
        if (selectEl) selectEl.value = '';
        if (startInput) startInput.value = '0';
        if (endInput) endInput.value = '0';

        if (headerText) headerText.textContent = "Add Figure to Sequence";
        if (headerIcon) headerIcon.textContent = "add_circle";
        if (submitBtn) submitBtn.textContent = "Save to Sequence";
        if (cancelBtn) cancelBtn.classList.add('hidden');
        return;
    }

    // Close notification dropdown when clicking outside
    if (!event.target.closest('#notification-bell-wrapper')) {
        const notifDropdown = document.getElementById('notification-dropdown');
        if (notifDropdown) notifDropdown.classList.add('hidden');
    }

    // 4. Close menus when clicking outside
    if (!event.target.closest('.js-menu-dropdown')) {
        document.querySelectorAll('.js-menu-dropdown').forEach(d => {
            d.classList.add('hidden');
        });
    }
});

/**
 * AppManager: Handles background tasks like polling and auto-logout.
 */
const AppManager = (function() {
    let lastActivityTime = Date.now();
    let lastPollTime = Date.now();
    
    // Constants (read from body data attributes, with fallbacks)
    let pollIntervalMs = 5 * 60 * 1000;
    let inactivityLogoutMs = 10 * 60 * 1000;
    const CHECK_INTERVAL_MS = 60 * 1000; // 1 minute
    
    function loadConfig() {
        if (document.body.dataset.pollInterval) {
            pollIntervalMs = parseInt(document.body.dataset.pollInterval) * 60 * 1000;
        }
        if (document.body.dataset.logoutInterval) {
            inactivityLogoutMs = parseInt(document.body.dataset.logoutInterval) * 60 * 1000;
        }
    }
    
    // Update activity timer
    function updateActivity() {
        lastActivityTime = Date.now();
    }
    
    function performChecks() {
        const now = Date.now();
        
        // 1. Auto-Logout Check
        if (now - lastActivityTime > inactivityLogoutMs) {
            console.log("Inactivity limit reached. Logging out...");
            const form = document.createElement('form');
            form.method = 'POST';
            form.action = '/logout';
            
            // Add CSRF token
            const tokenMeta = document.querySelector('meta[name="_csrf"]');
            if (tokenMeta) {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = '_csrf';
                input.value = tokenMeta.content;
                form.appendChild(input);
            }
            
            document.body.appendChild(form);
            form.submit();
            return; // Stop checking
        }
        
        // 2. Notification Polling Check
        if (now - lastPollTime >= pollIntervalMs) {
            if (!document.hidden) {
                lastPollTime = now;
                document.body.dispatchEvent(new Event('poll-notifications'));
            }
        }
    }
    
    function init() {
        loadConfig();
        // Listen for activity
        ['mousemove', 'keydown', 'touchstart', 'scroll'].forEach(evt => {
            document.addEventListener(evt, updateActivity, { passive: true });
        });
        
        // Listen for tab visibility changes
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) {
                // If tab becomes visible, check if we missed a poll
                if (Date.now() - lastPollTime >= pollIntervalMs) {
                    lastPollTime = Date.now();
                    document.body.dispatchEvent(new Event('poll-notifications'));
                }
            }
        });
        
        // Start background checks
        setInterval(performChecks, CHECK_INTERVAL_MS);
    }
    
    return { init };
})();

// Initialize AppManager
document.addEventListener('DOMContentLoaded', () => {
    AppManager.init();
});
