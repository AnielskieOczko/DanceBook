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
    const submitter = event.submitter;
    const message = (submitter && submitter.getAttribute('data-confirm')) || form.getAttribute('data-confirm');
    if (message && !confirm(message)) {
        event.preventDefault();
    }
});

/**
 * Generic auto-submit on change handler.
 * Usage: <select class="js-filter-select"> or <input class="js-auto-submit">
 */
document.addEventListener('change', function(event) {
    const autoSubmit = event.target.closest('.js-filter-select, .js-auto-submit, [data-auto-submit]');
    if (autoSubmit) {
        const form = autoSubmit.form || autoSubmit.closest('form');
        if (form) {
            form.submit();
            return;
        }
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

/**
 * Generic fallback for broken images, replacing inline onerror blocked by CSP.
 * Usage: <img src="..." data-fallback="/images/placeholder.png">
 */
window.addEventListener('error', function(event) {
    if (event.target && event.target.tagName === 'IMG') {
        const fallback = event.target.getAttribute('data-fallback');
        if (fallback && event.target.src && !event.target.src.endsWith(fallback)) {
            event.target.src = fallback;
        }
    }
}, true);

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
/**
 * A row's ⋯ menu in a desktop list table sits inside an overflow-x-auto wrapper, which clips an absolutely
 * positioned dropdown (the last rows showed it cut off, with a scrollbar). Inside such a wrapper the menu is
 * pinned to the viewport instead, under its button and right-aligned, or above it when there is no room below.
 * Scrolling or resizing closes it, since a pinned menu would no longer sit next to its button.
 */
function positionMenuInScrollContainer(btn, dropdown) {
    if (!btn.closest('.overflow-x-auto')) return;
    const rect = btn.getBoundingClientRect();
    const gap = 8;
    dropdown.style.position = 'fixed';
    dropdown.style.right = 'auto';
    dropdown.style.bottom = 'auto';
    dropdown.style.left = Math.max(gap, rect.right - dropdown.offsetWidth) + 'px';
    const below = rect.bottom + gap;
    const fitsBelow = below + dropdown.offsetHeight <= window.innerHeight - gap;
    dropdown.style.top = (fitsBelow ? below : Math.max(gap, rect.top - gap - dropdown.offsetHeight)) + 'px';
}

function closePinnedMenus() {
    document.querySelectorAll('.js-menu-dropdown:not(.hidden)').forEach(function(dropdown) {
        if (dropdown.style.position !== 'fixed') return;
        dropdown.classList.add('hidden');
        const btn = dropdown.previousElementSibling;
        if (btn && btn.classList.contains('js-menu-btn')) btn.setAttribute('aria-expanded', 'false');
    });
}
window.addEventListener('scroll', closePinnedMenus, true);
window.addEventListener('resize', closePinnedMenus);

function closeAllMenus(exceptElement) {
    // 1. Details dropdowns (home "+ New" menu, mobile profile menu, LOD color guide)
    document.querySelectorAll('details.js-dropdown[open], #home-new-menu details[open], #profile-menu[open], #lod-color-guide[open]').forEach(details => {
        if (!exceptElement || (!details.contains(exceptElement) && details !== exceptElement)) {
            details.removeAttribute('open');
            if (details.id === 'profile-menu') {
                resetProfileMenuLanguages(details);
            }
        }
    });

    // 2. 3-dot action menus (.js-menu-dropdown)
    document.querySelectorAll('.js-menu-dropdown:not(.hidden)').forEach(dropdown => {
        const btn = dropdown.previousElementSibling?.classList.contains('js-menu-btn')
            ? dropdown.previousElementSibling
            : dropdown.parentElement?.querySelector('.js-menu-btn');
        if (!exceptElement || (dropdown !== exceptElement && !dropdown.contains(exceptElement) && btn !== exceptElement && !btn?.contains(exceptElement))) {
            dropdown.classList.add('hidden');
            btn?.setAttribute('aria-expanded', 'false');
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

function resetProfileMenuLanguages(menu) {
    if (!menu) return;
    const langs = menu.querySelector('[data-langs]');
    const langBtn = menu.querySelector('[data-langbtn]');
    const chev = langBtn?.querySelector('.js-lang-chev');
    if (langs) langs.classList.add('hidden');
    if (langBtn) langBtn.setAttribute('aria-expanded', 'false');
    if (chev) chev.classList.remove('rotate-180');
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
        
        const dropdown = menuBtn.nextElementSibling?.classList.contains('js-menu-dropdown')
            ? menuBtn.nextElementSibling
            : menuBtn.parentElement?.querySelector('.js-menu-dropdown');
        if (!dropdown) return;
        
        const isHidden = dropdown.classList.contains('hidden');
        
        // Close all other dropdowns / menus
        closeAllMenus(menuBtn);
        
        // Toggle current menu
        if (isHidden) {
            dropdown.classList.remove('hidden');
            menuBtn.setAttribute('aria-expanded', 'true');
            positionMenuInScrollContainer(menuBtn, dropdown);
        } else {
            dropdown.classList.add('hidden');
            menuBtn.setAttribute('aria-expanded', 'false');
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

    // 10. Edit pinned figure handler
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

    // 11. Cancel edit pinned figure handler
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

    // Language accordion toggle in avatar dropdown
    const langBtn = event.target.closest('[data-langbtn]');
    if (langBtn) {
        event.preventDefault();
        const menu = langBtn.closest('#profile-menu');
        const langs = menu?.querySelector('[data-langs]');
        const chev = langBtn.querySelector('.js-lang-chev');
        if (langs) {
            const isHidden = langs.classList.contains('hidden');
            if (isHidden) {
                langs.classList.remove('hidden');
                langBtn.setAttribute('aria-expanded', 'true');
                chev?.classList.add('rotate-180');
            } else {
                langs.classList.add('hidden');
                langBtn.setAttribute('aria-expanded', 'false');
                chev?.classList.remove('rotate-180');
            }
        }
        return;
    }

    // Ensure redirect parameter in locale form reflects current URL
    const langRadio = event.target.closest('[data-langs] button[type="submit"]');
    if (langRadio) {
        const form = langRadio.closest('form');
        const redirectInput = form?.querySelector('input[name="redirect"]');
        if (redirectInput) {
            redirectInput.value = window.location.pathname + window.location.search;
        }
    }

    // Close dropdowns and menus when clicking outside
    closeAllMenus(event.target);
});

/**
 * Global keydown handler: closes open dropdown/popup menus on Escape and restores focus to trigger.
 */
document.addEventListener('keydown', function(event) {
    // Arrow key navigation in profile dropdown menu
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        const profileMenu = document.getElementById('profile-menu');
        if (profileMenu && profileMenu.hasAttribute('open') && profileMenu.contains(event.target)) {
            event.preventDefault();
            const items = Array.from(profileMenu.querySelectorAll('a[role="menuitem"], button[role="menuitem"], button[role="menuitemradio"]'))
                .filter(el => el.offsetParent !== null && !el.closest('.hidden'));
            if (items.length > 0) {
                const currentIndex = items.indexOf(document.activeElement);
                let nextIndex = 0;
                if (currentIndex === -1) {
                    nextIndex = event.key === 'ArrowDown' ? 0 : items.length - 1;
                } else if (event.key === 'ArrowDown') {
                    nextIndex = (currentIndex + 1) % items.length;
                } else {
                    nextIndex = (currentIndex - 1 + items.length) % items.length;
                }
                items[nextIndex].focus();
            }
            return;
        }

        // Arrow key navigation in 3-dot action menus (.js-menu-dropdown and .js-menu-btn)
        const menuBtn = event.target.closest('.js-menu-btn');
        if (menuBtn && event.key === 'ArrowDown') {
            event.preventDefault();
            const dropdown = menuBtn.nextElementSibling?.classList.contains('js-menu-dropdown')
                ? menuBtn.nextElementSibling
                : menuBtn.parentElement?.querySelector('.js-menu-dropdown');
            if (dropdown) {
                closeAllMenus(menuBtn);
                dropdown.classList.remove('hidden');
                menuBtn.setAttribute('aria-expanded', 'true');
                const items = Array.from(dropdown.querySelectorAll('a, button, [role="menuitem"]'))
                    .filter(el => el.offsetParent !== null && !el.disabled);
                if (items.length > 0) {
                    items[0].focus();
                }
            }
            return;
        }

        const menuDropdown = event.target.closest('.js-menu-dropdown');
        if (menuDropdown && !menuDropdown.classList.contains('hidden')) {
            event.preventDefault();
            const items = Array.from(menuDropdown.querySelectorAll('a, button, [role="menuitem"]'))
                .filter(el => el.offsetParent !== null && !el.disabled);
            if (items.length > 0) {
                const currentIndex = items.indexOf(document.activeElement);
                let nextIndex = 0;
                if (currentIndex === -1) {
                    nextIndex = event.key === 'ArrowDown' ? 0 : items.length - 1;
                } else if (event.key === 'ArrowDown') {
                    nextIndex = (currentIndex + 1) % items.length;
                } else {
                    nextIndex = (currentIndex - 1 + items.length) % items.length;
                }
                items[nextIndex].focus();
            }
            return;
        }
    }

    if (event.key !== 'Escape' && event.key !== 'Esc') return;
    if (event.target && event.target.closest('dialog')) return;

    let closedAny = false;
    let triggerToFocus = null;

    // 1. Details dropdowns (home "+ New" menu, mobile profile menu, LOD color guide)
    const openDetailsList = document.querySelectorAll('details.js-dropdown[open], #home-new-menu details[open], #profile-menu[open], #lod-color-guide[open]');
    openDetailsList.forEach(details => {
        details.removeAttribute('open');
        if (details.id === 'profile-menu') {
            resetProfileMenuLanguages(details);
        }
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
        btn?.setAttribute('aria-expanded', 'false');
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

// ── Mobile Filter Sheet & Active Chips (Issue #366) ──────────────────────────

// Open mobile filter sheet
document.addEventListener('click', function(event) {
    const openBtn = event.target.closest('.js-mobile-filter-open');
    if (openBtn) {
        const sheet = document.getElementById('filterSheet');
        if (sheet && typeof sheet.showModal === 'function') {
            sheet.showModal();
        }
    }
});

// Light-dismiss backdrop fallback for filterSheet dialog
document.addEventListener('click', function(event) {
    const sheet = event.target;
    if (sheet && sheet.id === 'filterSheet' && sheet.tagName === 'DIALOG' && sheet.open) {
        const rect = sheet.getBoundingClientRect();
        const isInside = (
            rect.top <= event.clientY &&
            event.clientY <= rect.top + rect.height &&
            rect.left <= event.clientX &&
            event.clientX <= rect.left + rect.width
        );
        if (!isInside) {
            sheet.close();
        }
    }
});

// Close filter sheet on apply
document.addEventListener('click', function(event) {
    const applyBtn = event.target.closest('.js-filter-sheet-apply');
    if (applyBtn) {
        const dialog = applyBtn.closest('dialog');
        if (dialog) dialog.close();
    }
});

// Sync changes inside mobile filter sheet to #filterForm
document.addEventListener('change', function(event) {
    const sheetFilter = event.target.closest('.js-sheet-filter');
    if (!sheetFilter) return;

    const filterName = sheetFilter.getAttribute('data-sheet-filter');
    if (!filterName) return;

    const form = document.getElementById('filterForm');
    if (!form) return;

    if (sheetFilter.type === 'checkbox') {
        const formCheckbox = form.querySelector(`input[name="${filterName}"][value="${sheetFilter.value}"]`);
        if (formCheckbox) {
            formCheckbox.checked = sheetFilter.checked;
            formCheckbox.dispatchEvent(new Event('change', { bubbles: true }));
        }
    } else if (sheetFilter.tagName === 'SELECT') {
        const formSelect = form.querySelector(`select[name="${filterName}"]`);
        if (formSelect) {
            formSelect.value = sheetFilter.value;
            formSelect.dispatchEvent(new Event('change', { bubbles: true }));
        }
    }
});

// Clear all active filters from sheet (preserves sort, search and view)
document.addEventListener('click', function(event) {
    const clearBtn = event.target.closest('.js-filter-sheet-clear');
    if (!clearBtn) return;

    const form = document.getElementById('filterForm');
    if (!form) return;

    const activeFilterNames = ['categoryIds', 'typeIds', 'danceClass', 'medalLevel', 'hasSteps', 'minRating'];

    activeFilterNames.forEach(function(name) {
        form.querySelectorAll(`input[type="checkbox"][name="${name}"]`).forEach(function(cb) {
            cb.checked = false;
        });
        const sel = form.querySelector(`select[name="${name}"]`);
        if (sel) sel.value = '';
    });

    const sheet = document.getElementById('filterSheet');
    if (sheet) {
        activeFilterNames.forEach(function(name) {
            sheet.querySelectorAll(`input[type="checkbox"][data-sheet-filter="${name}"]`).forEach(function(cb) {
                cb.checked = false;
            });
            const sel = sheet.querySelector(`select[data-sheet-filter="${name}"]`);
            if (sel) sel.value = '';
        });
    }

    if (window.htmx) {
        htmx.trigger(form, 'submit');
    } else {
        form.dispatchEvent(new Event('submit', { bubbles: true }));
    }
});

// Remove active filter chip
document.addEventListener('click', function(event) {
    const removeBtn = event.target.closest('.js-remove-chip');
    if (!removeBtn) return;

    const filterName = removeBtn.getAttribute('data-filter-name');
    const filterValue = removeBtn.getAttribute('data-filter-value');
    if (!filterName) return;

    const form = document.getElementById('filterForm');
    if (!form) return;

    if (filterName === 'categoryIds' || filterName === 'typeIds') {
        const cb = form.querySelector(`input[name="${filterName}"][value="${filterValue}"]`);
        if (cb) {
            cb.checked = false;
            cb.dispatchEvent(new Event('change', { bubbles: true }));
        }
        const sheetCb = document.querySelector(`#filterSheet input[data-sheet-filter="${filterName}"][value="${filterValue}"]`);
        if (sheetCb) {
            sheetCb.checked = false;
        }
    } else {
        const sel = form.querySelector(`select[name="${filterName}"]`);
        if (sel) {
            sel.value = '';
            sel.dispatchEvent(new Event('change', { bubbles: true }));
        }
        const sheetSel = document.querySelector(`#filterSheet select[data-sheet-filter="${filterName}"]`);
        if (sheetSel) {
            sheetSel.value = '';
        }
    }
});

// Trim tags in compact list rows that exceed available width and display +N marker
function updateRowTagOverflow() {
    const rows = document.querySelectorAll('.js-compact-row');
    rows.forEach(function(row) {
        const container = row.querySelector('.js-row-tags');
        if (!container) return;

        const existingMarker = container.querySelector('.js-tag-overflow');
        if (existingMarker) existingMarker.remove();

        const tags = Array.from(container.children);
        // Inline display, not the 'hidden' class: a badge carrying 'inline-flex' would win over it
        tags.forEach(function(tag) { tag.style.display = ''; });

        if (tags.length <= 1) return;

        const containerWidth = container.clientWidth;
        if (!containerWidth) return;

        if (container.scrollWidth > containerWidth) {
            const marker = document.createElement('span');
            marker.className = 'badge bg-surface-container text-on-surface-variant js-tag-overflow shrink-0';

            let hiddenCount = 0;
            for (let i = tags.length - 1; i >= 1; i--) {
                tags[i].style.display = 'none';
                hiddenCount++;
                marker.textContent = '+' + hiddenCount;
                if (!marker.parentNode) {
                    container.appendChild(marker);
                }
                if (container.scrollWidth <= containerWidth) {
                    break;
                }
            }
        }
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', updateRowTagOverflow);
} else {
    updateRowTagOverflow();
}
document.addEventListener('htmx:afterSwap', updateRowTagOverflow);

let resizeTagTimeout;
window.addEventListener('resize', function() {
    clearTimeout(resizeTagTimeout);
    resizeTagTimeout = setTimeout(updateRowTagOverflow, 100);
});

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
    if (status === 401) {
        window.location.href = '/login';
        return;
    }
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

// A link that points at a settings row (the admin "Needs attention" chips) opens that row as well as scrolling to it.
document.addEventListener('click', function(event) {
    const link = event.target.closest('a[data-opens-row]');
    if (!link) return;
    const row = document.getElementById(link.getAttribute('data-opens-row'));
    if (row && row.tagName === 'DETAILS') {
        row.open = true;
    }
});

// ════════════════════════════════════════════════
// TOP PROGRESS BAR (Issue #390)
// ════════════════════════════════════════════════
(function() {
    let progressBar = null;
    let activeHtmxRequests = 0;
    let finishTimer = null;
    // A PDF download leaves the page in place, so no pageshow ever ends a bar started for it (#416).
    let skipUnloadProgressUntil = 0;

    function isPdfDownload(link) {
        if (link.classList.contains('js-export-pdf')) return true;
        try {
            return new URL(link.href, window.location.href).pathname.endsWith('/pdf');
        } catch (e) {
            return false;
        }
    }

    function getProgressBar() {
        if (!progressBar) {
            progressBar = document.getElementById('top-progress-bar');
        }
        return progressBar;
    }

    function showProgressBar() {
        const bar = getProgressBar();
        if (!bar) return;
        if (finishTimer) {
            clearTimeout(finishTimer);
            finishTimer = null;
        }
        bar.classList.remove('is-finished');
        bar.classList.add('is-loading');
    }

    function finishProgressBar() {
        const bar = getProgressBar();
        if (!bar || !bar.classList.contains('is-loading')) return;
        bar.classList.remove('is-loading');
        bar.classList.add('is-finished');
        finishTimer = setTimeout(function() {
            bar.classList.remove('is-finished');
            finishTimer = null;
        }, 200);
    }

    // 1. HTMX request tracking (ignoring background notification polling)
    document.addEventListener('htmx:beforeRequest', function(event) {
        if (event.detail && event.detail.elt && event.detail.elt.id === 'notification-badge') {
            return;
        }
        activeHtmxRequests++;
        showProgressBar();
    });

    document.addEventListener('htmx:afterRequest', function(event) {
        if (event.detail && event.detail.elt && event.detail.elt.id === 'notification-badge') {
            return;
        }
        activeHtmxRequests = Math.max(0, activeHtmxRequests - 1);
        if (activeHtmxRequests === 0) {
            finishProgressBar();
        }
    });

    document.addEventListener('htmx:sendError', function(event) {
        if (event.detail && event.detail.elt && event.detail.elt.id === 'notification-badge') {
            return;
        }
        activeHtmxRequests = Math.max(0, activeHtmxRequests - 1);
        if (activeHtmxRequests === 0) {
            finishProgressBar();
        }
    });

    // 2. Full-page navigation tracking
    document.addEventListener('click', function(event) {
        const link = event.target.closest('a[href]');
        if (!link) return;
        if (link.getAttribute('target') === '_blank') return;
        if (link.hasAttribute('download')) return;
        if (isPdfDownload(link)) {
            skipUnloadProgressUntil = Date.now() + 3000;
            return;
        }
        if (link.getAttribute('hx-get') || link.getAttribute('hx-post')) return;
        const href = link.getAttribute('href');
        if (!href || href.startsWith('#') || href.startsWith('javascript:')) return;
        try {
            const url = new URL(link.href, window.location.href);
            if (url.origin !== window.location.origin) return;
        } catch (e) {
            return;
        }
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
        }
        showProgressBar();
    });

    document.addEventListener('submit', function(event) {
        const form = event.target;
        if (!form || typeof form.getAttribute !== 'function') return;
        if (form.getAttribute('hx-post') || form.getAttribute('hx-get') || form.getAttribute('hx-put') || form.getAttribute('hx-delete')) return;
        if (form.getAttribute('target') === '_blank') return;
        if (event.defaultPrevented) return;
        showProgressBar();
    });

    window.addEventListener('beforeunload', function() {
        if (Date.now() < skipUnloadProgressUntil) return;
        showProgressBar();
    });

    window.addEventListener('pageshow', function() {
        const bar = getProgressBar();
        if (bar) {
            bar.classList.remove('is-loading', 'is-finished');
        }
        activeHtmxRequests = 0;
    });

    window.TopProgressBar = {
        show: showProgressBar,
        finish: finishProgressBar,
        getElement: getProgressBar
    };
})();

// "Export PDF" on the Notes and Figures lists (#416): export what the filters currently show. The filter sheet
// already syncs into #filterForm, so its values are the truth; the view switch is not a filter.
document.addEventListener('click', function(event) {
    const link = event.target.closest('.js-export-pdf');
    if (!link) return;

    const form = document.getElementById('filterForm');
    const base = link.getAttribute('data-export-url');
    if (!form || !base) return;

    event.preventDefault();
    const params = new URLSearchParams();
    new FormData(form).forEach(function(value, name) {
        if (name === 'view' || typeof value !== 'string' || value === '') return;
        params.append(name, value);
    });
    const query = params.toString();
    window.location.href = query ? base + '?' + query : base;
});
