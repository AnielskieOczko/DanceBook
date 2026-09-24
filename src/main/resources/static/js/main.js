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
    if (target && target.closest('#confirmModalContainer')) {
        if (event.detail.successful) {
            const container = document.getElementById('confirmModalContainer');
            if (container) container.innerHTML = '';
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
        return;
    }

    // Bulk select-all handler
    const selectAll = event.target.closest('.js-select-all-sessions');
    if (selectAll) {
        const isChecked = selectAll.checked;
        const checkboxes = document.querySelectorAll('.js-session-checkbox');
        checkboxes.forEach(cb => { cb.checked = isChecked; });
        updateBulkActionBar();
        return;
    }

    // Bulk session checkbox handler
    const sessionCb = event.target.closest('.js-session-checkbox');
    if (sessionCb) {
        const checkboxes = document.querySelectorAll('.js-session-checkbox');
        const selectAllInput = document.querySelector('.js-select-all-sessions');
        if (selectAllInput && checkboxes.length > 0) {
            const allChecked = Array.from(checkboxes).every(cb => cb.checked);
            const someChecked = Array.from(checkboxes).some(cb => cb.checked);
            selectAllInput.checked = allChecked;
            selectAllInput.indeterminate = someChecked && !allChecked;
        }
        updateBulkActionBar();
        return;
    }
});

function updateBulkActionBar() {
    const checked = document.querySelectorAll('.js-session-checkbox:checked');
    const count = checked.length;
    const bar = document.getElementById('bulkActionBar');
    const countEl = document.getElementById('bulkSelectedCount');
    if (bar && countEl) {
        if (count > 0) {
            countEl.textContent = count === 1 ? '1 selected' : `${count} selected`;
            bar.classList.remove('hidden');
            bar.classList.add('flex');
        } else {
            bar.classList.add('hidden');
            bar.classList.remove('flex');
        }
    }
}

document.addEventListener('htmx:afterSwap', function(event) {
    if (event.detail.target && (event.detail.target.id === 'events-list' || event.detail.target.closest('#events-list'))) {
        updateBulkActionBar();
    }
});

/**
 * Global click handler for delegated events.
 */
document.addEventListener('click', function(event) {
    // Table sort header handler (Figures catalog)
    const sortBtn = event.target.closest('.js-sort-header');
    if (sortBtn) {
        event.preventDefault();
        const sortBy = sortBtn.getAttribute('data-sort-by');
        const sortSelect = document.getElementById('filterSortBy');
        if (sortSelect && sortBy) {
            sortSelect.value = sortBy;
            sortSelect.dispatchEvent(new Event('change', { bubbles: true }));
        }
        return;
    }

    // Bulk action clear selection handler
    const clearBtn = event.target.closest('.js-bulk-clear');
    if (clearBtn) {
        event.preventDefault();
        const checkboxes = document.querySelectorAll('.js-session-checkbox');
        checkboxes.forEach(cb => { cb.checked = false; });
        const selectAll = document.querySelector('.js-select-all-sessions');
        if (selectAll) {
            selectAll.checked = false;
            selectAll.indeterminate = false;
        }
        updateBulkActionBar();
        return;
    }

    // Dismiss banner handler
    const dismissBannerBtn = event.target.closest('.js-dismiss-banner');
    if (dismissBannerBtn) {
        event.preventDefault();
        const banner = dismissBannerBtn.closest('.js-banner');
        if (banner) banner.remove();
        return;
    }

    // Bulk edit add style segment handler
    const bulkAddSegmentBtn = event.target.closest('.js-bulk-add-segment');
    if (bulkAddSegmentBtn) {
        event.preventDefault();
        const container = bulkAddSegmentBtn.closest('.js-segments-container');
        if (container) {
            const rowsContainer = container.querySelector('.js-segment-rows');
            const template = container.querySelector('.js-segment-template');
            if (rowsContainer && template) {
                const index = rowsContainer.querySelectorAll('.js-segment-row').length;
                const clone = template.content.cloneNode(true);
                clone.querySelectorAll('select, input').forEach(field => {
                    if (field.name) field.name = field.name.replace('INDEX', index);
                });
                rowsContainer.appendChild(clone);
            }
        }
        return;
    }

    // Bulk edit remove style segment handler
    const bulkRemoveSegmentBtn = event.target.closest('.js-bulk-remove-segment');
    if (bulkRemoveSegmentBtn) {
        event.preventDefault();
        const row = bulkRemoveSegmentBtn.closest('.js-segment-row');
        const rowsContainer = row ? row.closest('.js-segment-rows') : null;
        if (row) row.remove();
        if (rowsContainer) {
            rowsContainer.querySelectorAll('.js-segment-row').forEach((r, idx) => {
                r.querySelectorAll('select, input').forEach(field => {
                    if (field.name) field.name = field.name.replace(/segments\[[^\]]*\]/, `segments[${idx}]`);
                });
            });
        }
        return;
    }

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

    // 2b. Generic show/hide toggle: a button carrying data-toggle-target="#id" flips the
    // `hidden` class on that element. Used by the collapsed filter panel on the training
    // agenda, where a responsive `hidden md:flex` keeps the desktop layout untouched.
    const toggleBtn = event.target.closest('.js-toggle');
    if (toggleBtn) {
        event.preventDefault();
        const target = document.querySelector(toggleBtn.dataset.toggleTarget);
        if (target) {
            target.classList.toggle('hidden');
            toggleBtn.setAttribute('aria-expanded', String(!target.classList.contains('hidden')));
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
        if (modal && typeof modal.showModal === 'function') modal.showModal();
        return;
    }

    // Dismiss alert button
    const dismissAlertBtn = event.target.closest('.js-dismiss-alert');
    if (dismissAlertBtn) {
        event.preventDefault();
        const alertBox = dismissAlertBtn.closest('[role="alert"]');
        if (alertBox) alertBox.remove();
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

/**
 * Dynamically hides and disables dance style checkboxes that do not match the selected category filters.
 */
function updateStyleFilters() {
    const categoryCheckboxes = document.querySelectorAll('.js-category-checkbox');
    if (!categoryCheckboxes.length) return; // Not on the materials page

    const checkedCategories = Array.from(categoryCheckboxes)
        .filter(cb => cb.checked)
        .map(cb => cb.value);

    const stylePills = document.querySelectorAll('.js-style-pill');
    
    stylePills.forEach(pill => {
        const categoryId = pill.getAttribute('data-category-id');
        const checkbox = pill.querySelector('.js-style-checkbox');
        
        if (checkedCategories.length === 0) {
            // No category filters selected -> show all styles
            pill.classList.remove('hidden');
            if (checkbox) {
                checkbox.removeAttribute('disabled');
            }
        } else {
            // Category filters selected -> check if this style's category matches any checked category
            if (checkedCategories.includes(categoryId)) {
                pill.classList.remove('hidden');
                if (checkbox) {
                    checkbox.removeAttribute('disabled');
                }
            } else {
                // Irrelevant category -> hide, disable, and uncheck
                pill.classList.add('hidden');
                if (checkbox) {
                    checkbox.setAttribute('disabled', 'true');
                    if (checkbox.checked) {
                        checkbox.checked = false;
                    }
                }
            }
        }
    });
}

// Initialize AppManager and filters on page load
document.addEventListener('DOMContentLoaded', () => {
    AppManager.init();
    updateStyleFilters();
});

// Capturing listener to update styles before HTMX/other event handlers run
document.addEventListener('change', function(event) {
    if (event.target.classList.contains('js-category-checkbox')) {
        updateStyleFilters();
    }
}, true);

// Open native dialogs swapped into confirmModalContainer
document.addEventListener('htmx:afterSwap', function(event) {
    if (event.target.id === 'confirmModalContainer') {
        const dialog = event.target.querySelector('dialog');
        if (dialog && typeof dialog.showModal === 'function') {
            dialog.showModal();
        }
    }
});

// Clear confirmModalContainer when its dialog closes
document.addEventListener('close', function(event) {
    if (event.target.tagName === 'DIALOG' && event.target.closest('#confirmModalContainer')) {
        const container = document.getElementById('confirmModalContainer');
        if (container) container.innerHTML = '';
    }
}, true);

/**
 * Renders an icon span matching the Thymeleaf fragments/icon contract.
 * @param {string} name - Material Symbol glyph name
 * @param {Object} [options] - Options: size ('sm'|'md'|'lg'), filled (boolean), cls (string), ariaLabel (string), id (string), title (string)
 * @returns {string} HTML string for the icon
 */
function renderIcon(name, options = {}) {
    const size = options.size || 'md';
    const sizeClass = size === 'sm' ? 'text-[16px]' : (size === 'lg' ? 'text-[24px]' : 'text-[20px]');
    const filledClass = options.filled ? ' icon-filled' : '';
    const cls = options.cls ? ' ' + options.cls : '';
    const idAttr = options.id ? ` id="${options.id}"` : '';
    const titleAttr = options.title ? ` title="${options.title}"` : '';
    const ariaAttr = options.ariaLabel ? `aria-label="${options.ariaLabel}" aria-hidden="false"` : 'aria-hidden="true"';
    return `<span${idAttr}${titleAttr} class="material-symbols-outlined shrink-0 select-none ${sizeClass}${filledClass}${cls}" ${ariaAttr}>${name}</span>`;
}

/**
 * Renders a visible error alert matching the fragments/alert style into #alert-container.
 * Replaces any existing message so only one message is displayed at a time.
 * @param {string} message - Error message to display
 */
function showErrorAlert(message) {
    let container = document.getElementById('alert-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'alert-container';
        container.className = 'mb-6';
        const main = document.querySelector('main');
        if (main) {
            main.insertBefore(container, main.firstChild);
        } else {
            document.body.insertBefore(container, document.body.firstChild);
        }
    }
    container.innerHTML = `
<div role="alert" class="p-4 rounded-md border flex items-start gap-3 bg-error/10 border-error text-error">
    ${renderIcon('error', { size: 'md' })}
    <div class="flex-1">
        <p class="text-sm font-normal">${message}</p>
    </div>
    <button type="button" class="js-dismiss-alert shrink-0 p-1 hover:opacity-80 transition-opacity" aria-label="Dismiss">
        ${renderIcon('close', { size: 'sm' })}
    </button>
</div>`;
}
window.showErrorAlert = showErrorAlert;

// Surface background HTMX failures in the alert container
document.body.addEventListener('htmx:responseError', function(event) {
    const status = event.detail.xhr ? event.detail.xhr.status : null;
    const message = status ? `Request failed (${status}). Please try again.` : 'An unexpected error occurred. Please try again.';
    showErrorAlert(message);
});

document.body.addEventListener('htmx:sendError', function() {
    showErrorAlert('Network error. Please check your connection and try again.');
});

// ════════════════════════════════════════════════
// TRIX RICH TEXT EDITOR
// ════════════════════════════════════════════════

// Prevent file drop & paste attachments entirely
document.addEventListener('trix-file-accept', function(event) {
    event.preventDefault();
});

// Configure and prune toolbar on editor initialization
document.addEventListener('trix-initialize', function(event) {
    const editor = event.target;
    const toolbar = editor.toolbarElement;
    if (!toolbar) return;

    // Prune disallowed buttons & button groups to keep only 5 controls:
    // bold, italic, link, bullet list, numbered list
    const disallowedSelectors = [
        '[data-trix-attribute="strike"]',
        '[data-trix-attribute="heading1"]',
        '[data-trix-attribute="quote"]',
        '[data-trix-attribute="code"]',
        '[data-trix-action="decreaseNestingLevel"]',
        '[data-trix-action="increaseNestingLevel"]',
        '[data-trix-button-group="file-tools"]',
        '[data-trix-button-group="history-tools"]'
    ];
    disallowedSelectors.forEach(sel => {
        toolbar.querySelectorAll(sel).forEach(el => el.remove());
    });

    // Ensure all remaining toolbar buttons have accessible names
    toolbar.querySelectorAll('button').forEach(btn => {
        const title = btn.getAttribute('title') || btn.textContent.trim();
        if (title && !btn.getAttribute('aria-label')) {
            btn.setAttribute('aria-label', title);
        }
    });
});

// Transfer focus from label to trix-editor
document.addEventListener('click', function(event) {
    const label = event.target.closest('label[for]');
    if (label) {
        const target = document.getElementById(label.getAttribute('for'));
        if (target && target.tagName === 'TRIX-EDITOR') {
            target.focus();
        }
    }
});

function isTrixEditorBlank(editor) {
    if (editor.editor) {
        return editor.editor.getDocument().toString().trim().length === 0;
    }
    const inputId = editor.getAttribute('input');
    const input = inputId ? document.getElementById(inputId) : null;
    if (input) {
        return input.value.replace(/<[^>]*>/g, '').trim().length === 0;
    }
    return true;
}

function findBlankRequiredTrixEditor(container) {
    const editors = container.querySelectorAll('trix-editor[data-required="true"]');
    for (const editor of editors) {
        if (isTrixEditorBlank(editor)) {
            return editor;
        }
    }
    return null;
}

// Intercept form submission if a required trix-editor is empty.
// Capture phase (useCapture = true) intercepts the submit event on document
// BEFORE it reaches any form-level listeners (including HTMX's submit listener), so
// stopImmediatePropagation() prevents HTMX from ever seeing the event or issuing a request.
document.addEventListener('submit', function(event) {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    const blankEditor = findBlankRequiredTrixEditor(form);
    if (blankEditor) {
        event.preventDefault();
        event.stopImmediatePropagation();
        blankEditor.focus();
    }
}, true);

// Additionally hook htmx:configRequest to guarantee no HTMX request leaves if issued from or inside
// a form with an empty required editor (e.g. if triggered programmatically or via non-submit triggers).
document.addEventListener('htmx:configRequest', function(event) {
    const elt = event.detail.elt;
    const form = elt instanceof HTMLFormElement ? elt : (elt ? elt.closest('form') : null);
    if (!form) return;
    const blankEditor = findBlankRequiredTrixEditor(form);
    if (blankEditor) {
        event.preventDefault();
        blankEditor.focus();
    }
});




