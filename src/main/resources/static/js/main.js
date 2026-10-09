/**
 * Client-side I18n dictionary and interpolation helper.
 * Reads the localized bundle injected into #i18n-bundle data-messages.
 */
const I18n = (function() {
    let messages = {};

    function init() {
        const el = document.getElementById('i18n-bundle');
        if (el) {
            try {
                const raw = el.getAttribute('data-messages');
                if (raw) messages = JSON.parse(raw);
            } catch (e) {
                console.error('Failed to parse i18n bundle', e);
            }
        }
    }

    init();
    document.addEventListener('DOMContentLoaded', init);

    function t(key, params) {
        if (!key) return '';
        let str = Object.prototype.hasOwnProperty.call(messages, key) ? messages[key] : key;
        if (params !== undefined && params !== null) {
            if (typeof params === 'object' && !Array.isArray(params)) {
                Object.keys(params).forEach(function(k) {
                    str = str.split('{' + k + '}').join(params[k]);
                });
            } else if (Array.isArray(params)) {
                params.forEach(function(val, idx) {
                    str = str.split('{' + idx + '}').join(val);
                });
            } else {
                str = str.split('{0}').join(params);
            }
        }
        return str;
    }

    return { init, t };
})();
window.I18n = I18n;

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
    // One-shot confirm dialogs close after their request; a dialog marked
    // data-keep-open (e.g. the figure picker) makes several and stays open.
    if (target && target.closest('#confirmModalContainer') && !target.closest('[data-keep-open]')) {
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
            countEl.textContent = count === 1 ? I18n.t('js.main.selected_one') : I18n.t('js.main.selected_many', count);
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
 * Closes all open dropdown/popup menus (details dropdowns, 3-dot menus, notification dropdown),
 * optionally preserving the menu that contains `exceptElement`.
 */
function closeAllMenus(exceptElement) {
    // 1. Details dropdowns (home "+ New" menu, mobile profile menu, LOD color guide)
    document.querySelectorAll('details.js-dropdown[open], #home-new-menu details[open], #profile-menu[open], #lod-color-guide[open]').forEach(details => {
        if (!exceptElement || (!details.contains(exceptElement) && details !== exceptElement)) {
            details.removeAttribute('open');
        }
    });

    // 2. 3-dot action menus (.js-menu-dropdown)
    document.querySelectorAll('.js-menu-dropdown:not(.hidden)').forEach(dropdown => {
        const btn = dropdown.previousElementSibling?.classList.contains('js-menu-btn')
            ? dropdown.previousElementSibling
            : dropdown.parentElement?.querySelector('.js-menu-btn');
        if (!exceptElement || (dropdown !== exceptElement && !dropdown.contains(exceptElement) && btn !== exceptElement && !btn?.contains(exceptElement))) {
            dropdown.classList.add('hidden');
        }
    });

    // 3. Notification dropdown
    const notifWrapper = document.getElementById('notification-bell-wrapper');
    const notifDropdown = document.getElementById('notification-dropdown');
    if (notifDropdown && !notifDropdown.classList.contains('hidden')) {
        if (!exceptElement || (notifWrapper && !notifWrapper.contains(exceptElement))) {
            notifDropdown.classList.add('hidden');
        }
    }
}

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
        
        const dropdown = menuBtn.nextElementSibling;
        if (!dropdown) return;
        
        const isHidden = dropdown.classList.contains('hidden');
        
        // Close all other dropdowns / menus
        closeAllMenus(menuBtn);
        
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
        const dropdown = document.getElementById('notification-dropdown');
        if (dropdown) {
            const isHidden = dropdown.classList.contains('hidden');
            closeAllMenus(bellBtn);
            if (isHidden) {
                dropdown.classList.remove('hidden');
            } else {
                dropdown.classList.add('hidden');
            }
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

        if (headerText) headerText.textContent = I18n.t('js.sequence.edit_figure');
        if (headerIcon) headerIcon.textContent = "edit";
        if (submitBtn) submitBtn.textContent = I18n.t('js.sequence.update_figure');
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

        if (headerText) headerText.textContent = I18n.t('js.sequence.add_figure');
        if (headerIcon) headerIcon.textContent = "add_circle";
        if (submitBtn) submitBtn.textContent = I18n.t('js.sequence.save_figure');
        if (cancelBtn) cancelBtn.classList.add('hidden');
        return;
    }

    // Close dropdowns and menus when clicking outside
    closeAllMenus(event.target);
});

/**
 * Global keydown handler: closes open dropdown/popup menus on Escape and restores focus to trigger.
 */
document.addEventListener('keydown', function(event) {
    if (event.key !== 'Escape' && event.key !== 'Esc') return;
    if (event.target && event.target.closest('dialog')) return;

    let closedAny = false;
    let triggerToFocus = null;

    // 1. Details dropdowns (home "+ New" menu, mobile profile menu, LOD color guide)
    const openDetailsList = document.querySelectorAll('details.js-dropdown[open], #home-new-menu details[open], #profile-menu[open], #lod-color-guide[open]');
    openDetailsList.forEach(details => {
        details.removeAttribute('open');
        closedAny = true;
        if (!triggerToFocus || details.contains(document.activeElement)) {
            triggerToFocus = details.querySelector('summary');
        }
    });

    // 2. 3-dot action menus (.js-menu-dropdown)
    const openMenuDropdowns = document.querySelectorAll('.js-menu-dropdown:not(.hidden)');
    openMenuDropdowns.forEach(dropdown => {
        dropdown.classList.add('hidden');
        closedAny = true;
        const btn = dropdown.previousElementSibling?.classList.contains('js-menu-btn')
            ? dropdown.previousElementSibling
            : dropdown.parentElement?.querySelector('.js-menu-btn');
        if (!triggerToFocus || dropdown.contains(document.activeElement)) {
            triggerToFocus = btn;
        }
    });

    // 3. Notification dropdown
    const notifDropdown = document.getElementById('notification-dropdown');
    if (notifDropdown && !notifDropdown.classList.contains('hidden')) {
        notifDropdown.classList.add('hidden');
        closedAny = true;
        const bellBtn = document.getElementById('notification-bell');
        if (!triggerToFocus || notifDropdown.contains(document.activeElement)) {
            triggerToFocus = bellBtn;
        }
    }

    if (closedAny) {
        event.preventDefault();
        triggerToFocus?.focus();
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
function showErrorAlert(message, params) {
    const text = I18n.t(message, params);
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
    const dismissLabel = I18n.t('common.dismiss');
    container.innerHTML = `
<div role="alert" class="p-4 rounded-md border flex items-start gap-3 bg-error/10 border-error text-error">
    ${renderIcon('error', { size: 'md' })}
    <div class="flex-1">
        <p class="text-sm font-normal">${text}</p>
    </div>
    <button type="button" class="js-dismiss-alert shrink-0 p-1 hover:opacity-80 transition-opacity" aria-label="${dismissLabel}">
        ${renderIcon('close', { size: 'sm' })}
    </button>
</div>`;
}
window.showErrorAlert = showErrorAlert;

// Surface background HTMX failures in the alert container
document.addEventListener('htmx:responseError', function(event) {
    const status = event.detail.xhr ? event.detail.xhr.status : null;
    if (status) {
        showErrorAlert('js.error.request_failed_status', status);
    } else {
        showErrorAlert('js.error.unexpected');
    }
});

document.addEventListener('htmx:sendError', function() {
    showErrorAlert('js.error.network');
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




// ── AI note rewrite ──────────────────────────────────────────────────────────
//
// Send the editor's text as it is right now, including unsaved edits. HTMX has
// already collected the request's parameters by the time htmx:configRequest
// fires, so writing into a form field here would only reach the *next* request;
// the value has to go into event.detail.parameters.
document.addEventListener('htmx:configRequest', function(event) {
    const btn = event.detail.elt;
    if (!btn || btn.id !== 'ai-rewrite-btn') return;
    const trixEditor = document.querySelector('trix-editor');
    if (!trixEditor) return;
    const inputId = trixEditor.getAttribute('input');
    const trixHiddenInput = inputId ? document.getElementById(inputId) : null;
    event.detail.parameters['currentText'] = trixHiddenInput ? trixHiddenInput.value : '';
});

// Delegated handler for Accept rewrite: copies the sanitised HTML into the
// Trix editor and removes the proposal panel.
document.addEventListener('click', function(event) {
    const btn = event.target.closest('#ai-rewrite-accept');
    if (!btn) return;
    const proposal = btn.getAttribute('data-proposal');
    if (!proposal) return;

    const trixEditor = document.querySelector('trix-editor');
    if (trixEditor) {
        const inputId = trixEditor.getAttribute('input');
        const trixHiddenInput = inputId ? document.getElementById(inputId) : null;
        if (trixHiddenInput) {
            trixHiddenInput.value = proposal;
            // Trix reads from its input on load but not on programmatic change;
            // loadHTML tells it to update its internal document.
            if (typeof trixEditor.editor !== 'undefined') {
                trixEditor.editor.loadHTML(proposal);
            }
        }
    }
    const panel = document.getElementById('ai-rewrite-target');
    if (panel) panel.innerHTML = '';
});

// Delegated handler for Decline: removes the proposal panel without touching the editor.
document.addEventListener('click', function(event) {
    const btn = event.target.closest('#ai-rewrite-decline');
    if (!btn) return;
    const panel = document.getElementById('ai-rewrite-target');
    if (panel) panel.innerHTML = '';
});

// ── Go-to-top / Go-to-bottom floating control (#224) ──────────────────────────
function initScrollToControls() {
    const controls = document.getElementById('scrollToControls');
    const topBtn = document.getElementById('scrollToTopBtn');
    const bottomBtn = document.getElementById('scrollToBottomBtn');
    if (!controls || !topBtn || !bottomBtn) return;

    function update() {
        const docEl = document.documentElement;
        const scrollHeight = Math.max(docEl.scrollHeight, document.body ? document.body.scrollHeight : 0);
        const clientHeight = window.innerHeight || docEl.clientHeight;
        const scrollTop = window.scrollY || docEl.scrollTop || (document.body ? document.body.scrollTop : 0);
        const maxScroll = scrollHeight - clientHeight;

        // Long page rule: page scrollable height exceeds ~2 screens
        const isLongPage = scrollHeight > (clientHeight * 2);

        if (!isLongPage || maxScroll <= 0) {
            controls.classList.remove('is-visible');
            controls.setAttribute('hidden', '');
            return;
        }

        controls.classList.add('is-visible');
        controls.removeAttribute('hidden');

        // At either end, the button that cannot move is disabled
        const atTop = scrollTop <= 8;
        const atBottom = (maxScroll - scrollTop) <= 8;

        topBtn.disabled = atTop;
        bottomBtn.disabled = atBottom;
    }

    topBtn.addEventListener('click', function() {
        const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        window.scrollTo({
            top: 0,
            behavior: prefersReducedMotion ? 'auto' : 'smooth'
        });
    });

    bottomBtn.addEventListener('click', function() {
        const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        const docEl = document.documentElement;
        const targetTop = Math.max(docEl.scrollHeight, document.body ? document.body.scrollHeight : 0);
        window.scrollTo({
            top: targetTop,
            behavior: prefersReducedMotion ? 'auto' : 'smooth'
        });
    });

    window.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update, { passive: true });
    document.addEventListener('htmx:afterSwap', update);
    document.addEventListener('htmx:afterSettle', update);
    document.addEventListener('htmx:historyRestore', update);

    if (typeof ResizeObserver !== 'undefined') {
        const resizeObserver = new ResizeObserver(update);
        resizeObserver.observe(document.documentElement);
    }

    update();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initScrollToControls);
} else {
    initScrollToControls();
}
