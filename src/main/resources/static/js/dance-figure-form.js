document.addEventListener('DOMContentLoaded', () => {
    // -------------------------------------------------------------
    // 1. Sticky Bar Live Previews & Dirty State Tracking
    // -------------------------------------------------------------
    const form = document.getElementById('figureForm');
    const edState = document.getElementById('edStateIndicator');
    let isDirty = false;

    function markDirty() {
        if (!isDirty && edState) {
            isDirty = true;
            edState.innerHTML = `<span class="w-2 h-2 rounded-full border-2 border-on-surface-variant shrink-0"></span><span>${I18n.t('figures.form.unsaved_changes')}</span>`;
        }
    }

    if (form) {
        form.addEventListener('input', markDirty);
        form.addEventListener('change', markDirty);
    }

    // Dance style change mapping & breadcrumb sync
    const danceTypeSelect = document.getElementById('danceTypeId');
    const crumbStyle = document.getElementById('crumbStyle');
    if (danceTypeSelect) {
        danceTypeSelect.addEventListener('change', (e) => {
            const danceTypeId = e.target.value;
            const selectedText = danceTypeSelect.options[danceTypeSelect.selectedIndex]?.text || '';
            if (crumbStyle) crumbStyle.textContent = selectedText;

            // Update flow add selects
            updateFlowDropdowns(danceTypeId);
        });
    }

    // Dance class preview in sticky header
    const danceClassSelect = document.getElementById('danceClass');
    const gradeClassBadge = document.getElementById('gradeClassBadge');
    if (danceClassSelect && gradeClassBadge) {
        danceClassSelect.addEventListener('change', (e) => {
            const val = e.target.value;
            gradeClassBadge.textContent = val ? I18n.t('figures.view.class_label', val) : I18n.t('figures.view.class_label', 'H');
        });
    }

    // Medal level preview in sticky header
    const medalRadios = document.querySelectorAll('.js-medal-radio');
    const gradeMedalBadge = document.getElementById('gradeMedalBadge');
    if (gradeMedalBadge) {
        medalRadios.forEach(radio => {
            radio.addEventListener('change', (e) => {
                const val = e.target.value.toLowerCase();
                gradeMedalBadge.innerHTML = renderMedalBadgeHtml(val);
            });
        });
    }

    // -------------------------------------------------------------
    // 2. Jump Navigation Smooth Scrolling & Active State
    // -------------------------------------------------------------
    const jumpLinks = document.querySelectorAll('.jump-nav-link');
    jumpLinks.forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            const targetId = link.getAttribute('data-jump');
            const targetEl = document.getElementById(targetId);
            if (!targetEl) return;

            const stickyHeader = document.querySelector('.sticky');
            const offset = (stickyHeader ? stickyHeader.offsetHeight : 0) + 12;
            const elementPosition = targetEl.getBoundingClientRect().top + window.scrollY;
            window.scrollTo({
                top: elementPosition - offset,
                behavior: 'smooth'
            });
        });
    });

    window.addEventListener('scroll', () => {
        const sections = document.querySelectorAll('[data-sec]');
        const stickyHeader = document.querySelector('.sticky');
        const offset = (stickyHeader ? stickyHeader.offsetHeight : 0) + 24;
        let activeSectionId = null;

        sections.forEach(sec => {
            const rect = sec.getBoundingClientRect();
            if (rect.top <= offset) {
                activeSectionId = sec.getAttribute('data-sec');
            }
        });

        if (activeSectionId) {
            jumpLinks.forEach(link => {
                const isCurrent = link.getAttribute('data-jump') === activeSectionId;
                link.classList.toggle('on', isCurrent);
            });
        }
    }, { passive: true });

    // -------------------------------------------------------------
    // 3. Step Sets Tab Switching (Desktop & Mobile)
    // -------------------------------------------------------------
    const tabsContainer = document.getElementById('step-sets-tabs-container');
    if (tabsContainer) {
        tabsContainer.addEventListener('click', (e) => {
            const tab = e.target.closest('.js-step-set-tab');
            if (tab) {
                const idx = tab.getAttribute('data-set-idx');
                setActiveStepSet(idx);
            }
        });
    }

    const mobileSetSelect = document.getElementById('step-sets-mobile-select');
    if (mobileSetSelect) {
        mobileSetSelect.addEventListener('change', (e) => {
            setActiveStepSet(e.target.value);
        });
    }

    const addStepSetBtn = document.getElementById('add-step-set-btn');
    if (addStepSetBtn) {
        addStepSetBtn.addEventListener('click', addStepSet);
    }

    // -------------------------------------------------------------
    // 4. Step Sets Panels Operations (Event Delegation)
    // -------------------------------------------------------------
    const panelsContainer = document.getElementById('step-sets-panels-container');
    if (panelsContainer) {
        // Combination name input sync with tabs and select
        panelsContainer.addEventListener('input', (e) => {
            const nameInput = e.target.closest('.js-set-name-input');
            if (nameInput) {
                const panel = nameInput.closest('.js-step-set-panel');
                const idx = panel.getAttribute('data-set-idx');
                const fallbackName = I18n.t('figures.form.steps.step_number', parseInt(idx) + 1);
                const val = nameInput.value.trim() || fallbackName;

                const tab = document.querySelector(`.js-step-set-tab[data-set-idx="${idx}"]`);
                if (tab) {
                    const display = tab.querySelector('.js-tab-name-display');
                    if (display) display.textContent = val;
                }

                if (mobileSetSelect) {
                    const opt = mobileSetSelect.querySelector(`option[value="${idx}"]`);
                    if (opt) {
                        const isDef = panel.querySelector('.js-set-default-radio')?.checked;
                        opt.textContent = val + (isDef ? ` (${I18n.t('figures.form.steps.mark_default')})` : '');
                    }
                }
            }
        });

        // Make default variant button
        panelsContainer.addEventListener('click', (e) => {
            const makeDefBtn = e.target.closest('.js-make-default-btn');
            if (makeDefBtn) {
                const panel = makeDefBtn.closest('.js-step-set-panel');
                const idx = panel.getAttribute('data-set-idx');
                updateDefaultSetSelection(idx);
            }
        });

        // Delete variant click (show inline confirm)
        panelsContainer.addEventListener('click', (e) => {
            const delBtn = e.target.closest('.js-remove-step-set-btn');
            if (delBtn) {
                const panel = delBtn.closest('.js-step-set-panel');
                const confirmBox = panel.querySelector('.js-del-confirm-box');
                if (confirmBox) {
                    const confirmText = confirmBox.querySelector('.js-del-confirm-text');
                    if (confirmText) {
                        const name = panel.querySelector('.js-set-name-input')?.value || '';
                        const stepsCount = panel.querySelectorAll('.step-row').length;
                        confirmText.textContent = I18n.t('figures.form.steps.delete_confirm', [name, stepsCount]);
                    }
                    confirmBox.classList.remove('hidden');
                }
            }
        });

        // Confirm delete variant
        panelsContainer.addEventListener('click', (e) => {
            const confirmBtn = e.target.closest('.js-confirm-del-btn');
            if (confirmBtn) {
                const panel = confirmBtn.closest('.js-step-set-panel');
                removeStepSet(panel);
            }
        });

        // Cancel delete variant
        panelsContainer.addEventListener('click', (e) => {
            const cancelBtn = e.target.closest('.js-cancel-del-btn');
            if (cancelBtn) {
                const panel = cancelBtn.closest('.js-step-set-panel');
                const confirmBox = panel.querySelector('.js-del-confirm-box');
                if (confirmBox) confirmBox.classList.add('hidden');
            }
        });

        // Role View Switcher inside panel (Leader, Follower, Side by side)
        panelsContainer.addEventListener('click', (e) => {
            const roleBtn = e.target.closest('.js-view-role-btn');
            if (roleBtn) {
                const panel = roleBtn.closest('.js-step-set-panel');
                const role = roleBtn.getAttribute('data-role');
                switchRoleView(panel, role);
            }
        });

        // Add Step Row button inside panel
        panelsContainer.addEventListener('click', (e) => {
            const addBtn = e.target.closest('.js-add-step-btn');
            if (addBtn) {
                const panel = addBtn.closest('.js-step-set-panel');
                const role = addBtn.getAttribute('data-role');
                addStepRow(panel, role);
            }
        });

        // Remove Step Row button
        panelsContainer.addEventListener('click', (e) => {
            const rmBtn = e.target.closest('.js-remove-step-btn');
            if (rmBtn) {
                removeStepRow(rmBtn);
            }
        });
    }

    // -------------------------------------------------------------
    // 5. Flow Section (Chips & Synchronized Hidden Multi-Select)
    // -------------------------------------------------------------
    document.querySelectorAll('.js-flow-add-select').forEach(select => {
        select.addEventListener('change', (e) => {
            const val = e.target.value;
            if (!val) return;
            const flowType = select.getAttribute('data-flow'); // "preceding" or "following"
            addFlowFigure(flowType, val);
            select.value = '';
        });
    });

    document.querySelectorAll('.js-chips-list').forEach(list => {
        list.addEventListener('click', (e) => {
            const rmBtn = e.target.closest('.js-remove-flow-chip');
            if (rmBtn) {
                const chip = rmBtn.closest('.js-flow-chip');
                const val = chip.getAttribute('data-fig');
                const flowType = list.getAttribute('data-flow');
                removeFlowFigure(flowType, val, chip);
            }
        });
    });

    // -------------------------------------------------------------
    // 6. Resources Links Management
    // -------------------------------------------------------------
    const addLinkBtn = document.getElementById('add-link-btn');
    if (addLinkBtn) {
        addLinkBtn.addEventListener('click', addLinkRow);
    }
    const linksTbody = document.getElementById('links-tbody');
    if (linksTbody) {
        linksTbody.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-remove-link-btn');
            if (btn) removeLinkRow(btn);
        });
    }

    // Initialize state on page load
    reindexStepSets();
    const checkedRadio = document.querySelector('.js-set-default-radio:checked');
    if (checkedRadio) {
        updateDefaultSetSelection(checkedRadio.value);
    } else {
        updateDefaultSetSelection("0");
    }

    // Auto-resize dense textareas to fit content with no inner scrollbar or clipping
    autoResizeAllTextareas();
    document.addEventListener('input', (e) => {
        if (e.target && e.target.classList.contains('dense-textarea')) {
            autoResizeTextarea(e.target);
        }
    });
    window.addEventListener('resize', () => {
        autoResizeAllTextareas();
    });
});

// Auto-resize dense textareas to fit content with no inner scrollbar or clipping
function autoResizeTextarea(textarea) {
    if (!textarea) return;
    textarea.style.height = 'auto';
    if (textarea.scrollHeight === 0) return;
    const style = window.getComputedStyle(textarea);
    const borderBoxDelta = (parseFloat(style.borderTopWidth) || 0) + (parseFloat(style.borderBottomWidth) || 0);
    const targetHeight = textarea.scrollHeight + borderBoxDelta + 4;
    textarea.style.height = Math.max(52, targetHeight) + 'px';
}

function autoResizeAllTextareas(container) {
    const root = container || document;
    root.querySelectorAll('.dense-textarea').forEach(autoResizeTextarea);
}

// -------------------------------------------------------------
// Medal Badge Render Helper (5 directions: A, B, C, D, D2)
// -------------------------------------------------------------
function getEffectiveMedalBadgeStyle() {
    const el = document.getElementById('gradeMedalBadge');
    if (el && el.getAttribute('data-badge-style')) return el.getAttribute('data-badge-style').toLowerCase();
    if (document.body && document.body.getAttribute('data-badge-style')) return document.body.getAttribute('data-badge-style').toLowerCase();
    return 'rank_bars';
}

function renderMedalBadgeHtml(lvl, customStyle) {
    if (!lvl) return '';
    const styleKey = (customStyle || getEffectiveMedalBadgeStyle()).toLowerCase();
    const nameMap = {
        bronze: typeof I18n !== 'undefined' ? I18n.t('figures.medal.bronze', 'Bronze') : 'Bronze',
        silver: typeof I18n !== 'undefined' ? I18n.t('figures.medal.silver', 'Silver') : 'Silver',
        gold: typeof I18n !== 'undefined' ? I18n.t('figures.medal.gold', 'Gold') : 'Gold'
    };
    const ariaMap = {
        bronze: typeof I18n !== 'undefined' ? I18n.t('figures.medal.aria.bronze', 'Bronze medal') : 'Bronze medal',
        silver: typeof I18n !== 'undefined' ? I18n.t('figures.medal.aria.silver', 'Silver medal') : 'Silver medal',
        gold: typeof I18n !== 'undefined' ? I18n.t('figures.medal.aria.gold', 'Gold medal') : 'Gold medal'
    };
    const rankMap = { bronze: 1, silver: 2, gold: 3 };
    const rank = rankMap[lvl] || 0;
    if (rank === 0) return '';

    const name = nameMap[lvl] || lvl;
    const ariaLabel = ariaMap[lvl] || `${name} medal`;
    const textClass = lvl === 'bronze' ? 'text-bronze' : (lvl === 'silver' ? 'text-silver' : 'text-gold');
    const sizeCls = 'w-[18px] h-[18px]';

    let svgContent = '';
    const viewBox = '0 0 16 16';

    if (styleKey === 'medal_shapes' || styleKey === 'b') {
        if (lvl === 'bronze') {
            svgContent = '<circle cx="8" cy="8" r="6.2" fill="currentColor"/>';
        } else if (lvl === 'silver') {
            svgContent = '<rect x="2.6" y="2.6" width="10.8" height="10.8" transform="rotate(45 8 8)" fill="currentColor"/>';
        } else {
            svgContent = '<polygon points="8.00,0.40 9.99,3.20 13.37,2.63 12.80,6.01 15.60,8.00 12.80,9.99 13.37,13.37 9.99,12.80 8.00,15.60 6.01,12.80 2.63,13.37 3.20,9.99 0.40,8.00 3.20,6.01 2.63,2.63 6.01,3.20" fill="currentColor"/>';
        }
    } else if (styleKey === 'ring_and_word' || styleKey === 'c') {
        svgContent = `
            <path d="M9.45 2.18 A6 6 0 0 1 13.77 9.65" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>
            ${rank >= 2
                ? '<path d="M12.32 12.17 A6 6 0 0 1 3.68 12.17" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>'
                : '<path d="M12.32 12.17 A6 6 0 0 1 3.68 12.17" class="stroke-medal-empty" stroke-width="2.6" stroke-linecap="round"/>'}
            ${rank >= 3
                ? '<path d="M2.23 9.65 A6 6 0 0 1 6.55 2.18" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>'
                : '<path d="M2.23 9.65 A6 6 0 0 1 6.55 2.18" class="stroke-medal-empty" stroke-width="2.6" stroke-linecap="round"/>'}
        `;
    } else if (styleKey === 'medal_icon' || styleKey === 'd' || styleKey === 'medal_no_numerals' || styleKey === 'd2') {
        const num = (styleKey === 'medal_icon' || styleKey === 'd') ? (rank === 3 ? '1' : (rank === 2 ? '2' : '3')) : '';
        const softFillClass = lvl === 'bronze' ? 'fill-bronze-soft' : (lvl === 'silver' ? 'fill-silver-soft' : 'fill-gold-soft');
        svgContent = `
            <path d="M4.8 0.6 L7.5 5.6 M11.2 0.6 L8.5 5.6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="butt"/>
            <circle cx="8" cy="10.3" r="5.3" class="${softFillClass}" stroke="currentColor" stroke-width="1"/>
            <circle cx="8" cy="10.3" r="4" fill="none" stroke="currentColor" stroke-width="0.5" opacity="0.55"/>
            ${num ? `<text x="8" y="12.7" text-anchor="middle" font-family="Inter,system-ui,sans-serif" font-weight="700" font-size="7" fill="currentColor">${num}</text>` : ''}
        `;
    } else {
        svgContent = `
            <rect x="1" y="10" width="4" height="5" rx="1.2" fill="currentColor" stroke="currentColor" stroke-width="1.2"/>
            ${rank >= 2
                ? '<rect x="6" y="6" width="4" height="9" rx="1.2" fill="currentColor" stroke="currentColor" stroke-width="1.2"/>'
                : '<rect x="6" y="6" width="4" height="9" rx="1.2" fill="none" class="stroke-medal-empty" stroke-width="1.2"/>'}
            ${rank >= 3
                ? '<rect x="11" y="2" width="4" height="13" rx="1.2" fill="currentColor" stroke="currentColor" stroke-width="1.2"/>'
                : '<rect x="11" y="2" width="4" height="13" rx="1.2" fill="none" class="stroke-medal-empty" stroke-width="1.2"/>'}
        `;
    }

    return `<span class="inline-flex items-center gap-1.5 align-middle font-semibold text-[13px] leading-none whitespace-nowrap ${textClass}" role="img" aria-label="${ariaLabel}" title="${ariaLabel}">
        <svg class="block shrink-0 ${sizeCls}" viewBox="${viewBox}" fill="none" aria-hidden="true">${svgContent}</svg>
        <span>${name}</span>
    </span>`;
}

// -------------------------------------------------------------
// Step Set Functions
// -------------------------------------------------------------
function setActiveStepSet(index) {
    document.querySelectorAll('.js-step-set-tab').forEach(tab => {
        const idx = tab.getAttribute('data-set-idx');
        const isActive = idx === index.toString();
        tab.classList.toggle('active', isActive);
        tab.setAttribute('aria-selected', isActive ? 'true' : 'false');
    });

    const mobileSelect = document.getElementById('step-sets-mobile-select');
    if (mobileSelect && mobileSelect.value !== index.toString()) {
        mobileSelect.value = index.toString();
    }

    document.querySelectorAll('.js-step-set-panel').forEach(panel => {
        const idx = panel.getAttribute('data-set-idx');
        const isMatch = idx === index.toString();
        panel.classList.toggle('hidden', !isMatch);
        if (isMatch) {
            autoResizeAllTextareas(panel);
        }
    });
}

function updateDefaultSetSelection(selectedIndex) {
    const panels = document.querySelectorAll('.js-step-set-panel');
    panels.forEach((panel, idx) => {
        const defaultHidden = panel.querySelector('.js-set-default-hidden');
        const defaultRadio = panel.querySelector('.js-set-default-radio');
        const isDefault = idx === parseInt(selectedIndex);

        if (defaultHidden) defaultHidden.value = isDefault ? "true" : "false";
        if (defaultRadio) defaultRadio.checked = isDefault;

        // Button vs Badge
        const badge = panel.querySelector('.js-default-badge');
        const makeDefBtn = panel.querySelector('.js-make-default-btn');
        if (badge) badge.classList.toggle('hidden', !isDefault);
        if (makeDefBtn) makeDefBtn.classList.toggle('hidden', isDefault);

        // Tab indicator
        const tab = document.querySelector(`.js-step-set-tab[data-set-idx="${idx}"]`);
        if (tab) {
            const indicator = tab.querySelector('.js-tab-default-indicator');
            if (indicator) indicator.classList.toggle('hidden', !isDefault);
        }

        // Mobile select option label
        const mobileSelect = document.getElementById('step-sets-mobile-select');
        if (mobileSelect) {
            const opt = mobileSelect.querySelector(`option[value="${idx}"]`);
            if (opt) {
                const nameInput = panel.querySelector('.js-set-name-input');
                const baseName = nameInput ? nameInput.value.trim() : I18n.t('figures.form.steps.variant_number', idx + 1);
                opt.textContent = baseName + (isDefault ? I18n.t('figures.form.steps.default_suffix') : '');
            }
        }
    });
}

function switchRoleView(panel, role) {
    panel.querySelectorAll('.js-view-role-btn').forEach(btn => {
        const btnRole = btn.getAttribute('data-role');
        const isActive = btnRole === role;
        btn.classList.toggle('active', isActive);
        btn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
    });

    const isBoth = role === 'both';
    const isLeader = role === 'LEADER';
    const isFollower = role === 'FOLLOWER';

    const tableWrapper = panel.querySelector('.js-steps-table-wrapper');
    if (tableWrapper) {
        tableWrapper.classList.toggle('norole', !isBoth);
    }

    const header = panel.querySelector('.js-steps-header');
    const headerRoleCol = panel.querySelector('.js-col-header-role');
    const roleCols = panel.querySelectorAll('.js-col-role');
    const mobileRoleChips = panel.querySelectorAll('.js-mobile-role-chip');
    const addLeaderBtn = panel.querySelector('.js-add-leader-btn');
    const addFollowerBtn = panel.querySelector('.js-add-follower-btn');

    const colsBoth = '36px 104px 64px 64px minmax(110px, 1.1fr) 70px minmax(100px, .8fr) minmax(170px, 1.3fr) minmax(170px, 1.4fr) 36px';
    const colsSingle = '36px 64px 64px minmax(110px, 1.1fr) 70px minmax(100px, .8fr) minmax(170px, 1.3fr) minmax(170px, 1.4fr) 36px';

    if (header) {
        header.style.gridTemplateColumns = isBoth ? colsBoth : colsSingle;
    }
    if (headerRoleCol) {
        headerRoleCol.classList.toggle('hidden', !isBoth);
    }

    roleCols.forEach(col => col.classList.toggle('hidden', !isBoth));
    mobileRoleChips.forEach(chip => chip.classList.toggle('hidden', !isBoth));

    panel.querySelectorAll('.step-row').forEach(row => {
        const rowRole = row.getAttribute('data-role');
        const show = isBoth || (isLeader && rowRole === 'LEADER') || (isFollower && rowRole === 'FOLLOWER');
        row.classList.toggle('hidden', !show);
        row.style.gridTemplateColumns = isBoth ? colsBoth : colsSingle;
    });

    if (addLeaderBtn) addLeaderBtn.classList.toggle('hidden', isFollower);
    if (addFollowerBtn) addFollowerBtn.classList.toggle('hidden', isLeader);
    autoResizeAllTextareas(panel);
}

function reindexStepSets() {
    const panels = document.querySelectorAll('.js-step-set-panel');
    const tabs = document.querySelectorAll('.js-step-set-tab');
    const variantsNote = document.getElementById('variantsCountNote');
    if (variantsNote) {
        variantsNote.textContent = I18n.t('figures.form.steps.variants_count', panels.length);
    }

    panels.forEach((panel, setIdx) => {
        panel.setAttribute('data-set-idx', setIdx);

        panel.querySelectorAll('.step-set-id').forEach(el => el.name = `stepSets[${setIdx}].id`);
        const nameInput = panel.querySelector('.js-set-name-input');
        if (nameInput) nameInput.name = `stepSets[${setIdx}].name`;

        const radio = panel.querySelector('.js-set-default-radio');
        if (radio) {
            radio.id = `set-default-radio-${setIdx}`;
            radio.value = setIdx;
        }

        const defaultHidden = panel.querySelector('.js-set-default-hidden');
        if (defaultHidden) defaultHidden.name = `stepSets[${setIdx}].isDefault`;

        // Number leader and follower steps
        const leaderRows = panel.querySelectorAll('.step-row[data-role="LEADER"]');
        leaderRows.forEach((row, i) => {
            const num = i + 1;
            const display = row.querySelector('.step-number-display');
            if (display) display.textContent = num;
            const displayFull = row.querySelector('.step-number-display-full');
            if (displayFull) displayFull.textContent = I18n.t('figures.form.steps.step_number', num);
            const numInput = row.querySelector('.step-number');
            if (numInput) numInput.value = num;
            row.setAttribute('data-step-number', num);
            row.style.order = num * 2;
        });

        const followerRows = panel.querySelectorAll('.step-row[data-role="FOLLOWER"]');
        followerRows.forEach((row, i) => {
            const num = i + 1;
            const display = row.querySelector('.step-number-display');
            if (display) display.textContent = num;
            const displayFull = row.querySelector('.step-number-display-full');
            if (displayFull) displayFull.textContent = I18n.t('figures.form.steps.step_number', num);
            const numInput = row.querySelector('.step-number');
            if (numInput) numInput.value = num;
            row.setAttribute('data-step-number', num);
            row.style.order = num * 2 + 1;
        });

        const leaderCount = leaderRows.length;
        const followerCount = followerRows.length;
        const lCountEl = panel.querySelector('.js-leader-count');
        const fCountEl = panel.querySelector('.js-follower-count');
        if (lCountEl) lCountEl.textContent = leaderCount;
        if (fCountEl) fCountEl.textContent = followerCount;

        // Empty box display
        const totalSteps = leaderCount + followerCount;
        const emptyBox = panel.querySelector('.js-steps-empty-box');
        const tableWrapper = panel.querySelector('.js-steps-table-wrapper');
        const addLeaderBtn = panel.querySelector('.js-add-leader-btn');
        const addFollowerBtn = panel.querySelector('.js-add-follower-btn');

        if (emptyBox && tableWrapper) {
            if (totalSteps === 0) {
                emptyBox.classList.remove('hidden');
                tableWrapper.classList.add('hidden');
                if (addLeaderBtn) addLeaderBtn.classList.add('hidden');
                if (addFollowerBtn) addFollowerBtn.classList.add('hidden');
            } else {
                emptyBox.classList.add('hidden');
                tableWrapper.classList.remove('hidden');
                // Role view visibility decides add buttons
                const activeRoleBtn = panel.querySelector('.js-view-role-btn.active');
                const curRole = activeRoleBtn ? activeRoleBtn.getAttribute('data-role') : 'LEADER';
                switchRoleView(panel, curRole);
            }
        }

        // Reindex all inputs under this set
        const allStepsInSet = panel.querySelectorAll('.step-row');
        allStepsInSet.forEach((row, stepIdx) => {
            row.querySelectorAll('input, select, textarea').forEach(input => {
                const name = input.name;
                if (name) {
                    input.name = name.replace(/stepSets\[\d+\].steps\[\d+\]/, `stepSets[${setIdx}].steps[${stepIdx}]`);
                }
            });
        });
    });

    tabs.forEach((tab, setIdx) => {
        tab.setAttribute('data-set-idx', setIdx);
    });
}

function addStepSet() {
    const panelsContainer = document.getElementById('step-sets-panels-container');
    const tabsContainer = document.getElementById('step-sets-tabs-container');
    const mobileSelect = document.getElementById('step-sets-mobile-select');
    if (!panelsContainer) return;

    const setIdx = document.querySelectorAll('.js-step-set-panel').length;
    const setName = I18n.t('figures.form.steps.variant_number', setIdx + 1);

    // Create desktop tab
    if (tabsContainer) {
        const tab = document.createElement('button');
        tab.type = 'button';
        tab.className = 'set-tabs-btn js-step-set-tab';
        tab.setAttribute('data-set-idx', setIdx);
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-selected', 'false');
        tab.innerHTML = `
            <span class="js-tab-name-display">${setName}</span>
            <span class="js-tab-default-indicator text-primary inline-flex items-center hidden">${renderIcon('check', { size: 'sm' })}</span>
        `;
        tabsContainer.appendChild(tab);
    }

    // Create mobile select option
    if (mobileSelect) {
        const opt = document.createElement('option');
        opt.value = setIdx;
        opt.textContent = setName;
        mobileSelect.appendChild(opt);
    }

    // Create panel
    const panel = document.createElement('div');
    panel.className = 'js-step-set-panel space-y-4 hidden';
    panel.setAttribute('data-set-idx', setIdx);
    panel.innerHTML = `
        <input type="hidden" class="step-set-id" name="stepSets[${setIdx}].id" value="" />
        <div class="grid grid-cols-1 md:grid-cols-[minmax(0,340px)_1fr] gap-3 md:gap-6 items-end">
            <div>
                <label class="block text-sm font-semibold text-on-surface mb-1.5">${I18n.t('figures.form.steps.combination_name')}</label>
                <input type="text" name="stepSets[${setIdx}].name" value="${setName}" required
                       class="dense-input js-set-name-input" placeholder="${I18n.t('figures.form.steps.combination_name_placeholder')}" />
            </div>
            <div class="flex flex-wrap gap-2 sm:gap-3 items-center justify-start md:justify-end min-h-[44px]">
                <input type="radio" id="set-default-radio-${setIdx}" name="default-set-radio"
                       value="${setIdx}" class="sr-only js-set-default-radio" />
                <input type="hidden" class="js-set-default-hidden" name="stepSets[${setIdx}].isDefault" value="false" />
                <span class="js-default-badge text-primary inline-flex items-center gap-1.5 font-semibold text-[13px] hidden">
                    ${renderIcon('check', { size: 'sm' })}
                    <span>${I18n.t('figures.form.steps.mark_default')}</span>
                </span>
                <button type="button" class="btn-outline btn-sm js-make-default-btn">
                    <span>${I18n.t('figures.form.steps.mark_default')}</span>
                </button>
                <button type="button" class="btn-ghost btn-sm text-on-surface-variant hover:text-danger js-remove-step-set-btn" title="${I18n.t('figures.form.steps.delete_combination')}">
                    ${renderIcon('delete', { size: 'sm' })}
                    <span>${I18n.t('figures.form.steps.delete_combination')}</span>
                </button>
            </div>
        </div>
        <div class="js-del-confirm-box hidden p-3 rounded-card bg-danger-soft border border-danger/30 text-xs flex flex-wrap items-center justify-between gap-3" role="alertdialog" aria-label="${I18n.t('figures.form.steps.confirm_delete_title')}">
            <span class="text-on-surface js-del-confirm-text">${I18n.t('figures.form.steps.delete_confirm_generic')}</span>
            <div class="flex gap-2">
                <button type="button" class="btn-danger btn-sm py-1 px-3 text-xs js-confirm-del-btn">${I18n.t('figures.form.steps.delete_combination')}</button>
                <button type="button" class="btn-ghost btn-sm py-1 px-3 text-xs js-cancel-del-btn">${I18n.t('figures.form.steps.keep_variant')}</button>
            </div>
        </div>
        <div class="flex flex-wrap items-center justify-between gap-3 pt-2">
            <div class="seg-pill max-lg:w-full max-lg:flex" role="group" aria-label="${I18n.t('figures.form.aria.which_role')}">
                <button type="button" class="seg-pill-btn max-lg:flex-1 max-lg:justify-center js-view-role-btn active" data-role="LEADER" aria-pressed="true">
                    <span>${I18n.t('figures.role.leader')}</span>
                    <span class="count text-xs font-semibold opacity-75 js-leader-count">0</span>
                </button>
                <button type="button" class="seg-pill-btn max-lg:flex-1 max-lg:justify-center js-view-role-btn" data-role="FOLLOWER" aria-pressed="false">
                    <span>${I18n.t('figures.role.follower')}</span>
                    <span class="count text-xs font-semibold opacity-75 js-follower-count">0</span>
                </button>
                <button type="button" class="seg-pill-btn max-lg:flex-1 max-lg:justify-center js-view-role-btn" data-role="both" aria-pressed="false">
                    <span>${I18n.t('figures.form.steps.side_by_side')}</span>
                </button>
            </div>
            <span class="text-[13px] text-on-surface-variant">${I18n.t('figures.form.steps.required_note')}</span>
        </div>
        <div class="js-steps-empty-box p-8 text-center border border-dashed border-outline rounded-card flex flex-col gap-3 items-center">
            <h3 class="text-base font-semibold text-on-surface">${I18n.t('figures.form.steps.empty_title')}</h3>
            <p class="text-sm text-on-surface-variant max-w-[46ch]">${I18n.t('figures.form.steps.empty_desc')}</p>
            <div class="flex flex-wrap gap-3 justify-center pt-2">
                <button type="button" class="btn-primary btn-sm js-add-step-btn" data-role="LEADER">
                    ${renderIcon('add', { size: 'sm' })}
                    <span>${I18n.t('figures.form.steps.add_leader_step')}</span>
                </button>
                <button type="button" class="btn-outline btn-sm js-add-step-btn" data-role="FOLLOWER">
                    ${renderIcon('add', { size: 'sm' })}
                    <span>${I18n.t('figures.form.steps.add_follower_step')}</span>
                </button>
            </div>
        </div>
        <div class="js-steps-table-wrapper w-full steps-table norole hidden">
            <div class="hidden lg:grid gap-2 items-center pb-2 border-b border-outline text-xs font-semibold text-on-surface-variant js-steps-header"
                 style="grid-template-columns: var(--cols);">
                <div class="text-center">${I18n.t('figures.form.steps.th.number')}</div>
                <div class="js-col-header-role hidden pl-3.5">${I18n.t('figures.view.steps.th.role')}</div>
                <div class="pl-3.5">${I18n.t('figures.form.steps.th.timing')}</div>
                <div class="pl-3.5">${I18n.t('figures.form.steps.th.foot')}</div>
                <div class="pl-3.5">${I18n.t('figures.form.steps.th.action')}</div>
                <div class="pl-3.5">${I18n.t('figures.form.steps.th.footwork')}</div>
                <div class="pl-3.5">${I18n.t('figures.form.steps.th.alignment')}</div>
                <div class="pl-3.5">${I18n.t('figures.form.steps.th.turn')}</div>
                <div class="pl-3.5">${I18n.t('figures.form.steps.th.comments')}</div>
                <div class="text-center"><span class="sr-only">${I18n.t('figures.form.steps.remove_step')}</span></div>
            </div>
            <div class="js-steps-rows-container divide-y divide-border">
                <div class="js-leader-steps-tbody contents"></div>
                <div class="js-follower-steps-tbody contents"></div>
            </div>
        </div>
        <div class="flex flex-wrap gap-2 sm:gap-3 items-center pt-2">
            <button type="button" class="btn-outline btn-sm js-add-step-btn js-add-leader-btn" data-role="LEADER">
                ${renderIcon('add', { size: 'sm' })}
                <span>${I18n.t('figures.form.steps.add_leader_step')}</span>
            </button>
            <button type="button" class="btn-outline btn-sm js-add-step-btn js-add-follower-btn" data-role="FOLLOWER">
                ${renderIcon('add', { size: 'sm' })}
                <span>${I18n.t('figures.form.steps.add_follower_step')}</span>
            </button>
        </div>
    `;
    panelsContainer.appendChild(panel);

    if (setIdx === 0) {
        updateDefaultSetSelection("0");
    }

    reindexStepSets();
    setActiveStepSet(setIdx.toString());
}

function removeStepSet(panel) {
    const panels = document.querySelectorAll('.js-step-set-panel');
    if (panels.length <= 1) {
        alert(I18n.t('js.figure.alert_combination_required'));
        return;
    }

    const idxToRemove = parseInt(panel.getAttribute('data-set-idx'));
    const tabToRemove = document.querySelector(`.js-step-set-tab[data-set-idx="${idxToRemove}"]`);
    const mobileSelect = document.getElementById('step-sets-mobile-select');
    if (mobileSelect) {
        const opt = mobileSelect.querySelector(`option[value="${idxToRemove}"]`);
        if (opt) opt.remove();
    }

    const wasDefault = panel.querySelector('.js-set-default-radio').checked;
    panel.remove();
    if (tabToRemove) tabToRemove.remove();

    if (wasDefault) {
        updateDefaultSetSelection("0");
    }

    reindexStepSets();
    setActiveStepSet("0");
}

function addStepRow(panel, role) {
    const isLeader = role === 'LEADER';
    const tbody = panel.querySelector(`.js-${role.toLowerCase()}-steps-tbody`);
    if (!tbody) return;

    const tr = document.createElement('div');
    tr.className = `step-row py-4.5 lg:py-1.5 flex flex-wrap lg:grid gap-3 lg:gap-2 items-start border-b border-border ${!isLeader ? 'step-row-follower' : ''}`;
    tr.style.gridTemplateColumns = 'var(--cols)';
    tr.setAttribute('data-role', role);

    const roleName = isLeader ? I18n.t('figures.role.leader') : I18n.t('figures.role.follower');
    const roleChipHtml = isLeader
        ? `<span class="role-chip"><i class="w-[18px] h-[18px] rounded-full flex items-center justify-center not-italic text-[11px] bg-on-surface text-surface">L</i>${roleName}</span>`
        : `<span class="role-chip"><i class="w-[18px] h-[18px] rounded-full flex items-center justify-center not-italic text-[11px] bg-surface text-on-surface ring-1 ring-inset ring-on-surface">F</i>${roleName}</span>`;

    tr.innerHTML = `
        <input type="hidden" class="step-id" name="stepSets[0].steps[0].id" value="" />
        <input type="hidden" class="step-role" name="stepSets[0].steps[0].role" value="${role}" />
        <input type="hidden" class="step-number" name="stepSets[0].steps[0].stepNumber" value="1" />

        <div class="c-head flex lg:contents items-center gap-2.5 w-full">
            <div class="c-n pt-0 lg:pt-2 text-left lg:text-center font-semibold text-[15px] lg:text-[13px] text-on-surface">
                <span class="max-lg:hidden step-number-display">1</span>
                <span class="lg:hidden step-number-display-full">${I18n.t('figures.form.steps.step_number', 1)}</span>
            </div>

            <div class="c-role js-col-role hidden pt-0 lg:pt-1">
                ${roleChipHtml}
            </div>

            <div class="c-x ml-auto lg:ml-0 lg:order-99 pt-0 lg:pt-1 text-center">
                <button type="button" class="btn-icon text-on-surface-variant hover:text-danger js-remove-step-btn" aria-label="${I18n.t('figures.form.steps.remove_step')}">
                    ${renderIcon('delete', { size: 'sm' })}
                </button>
            </div>
        </div>

        <div class="min-w-0 basis-[calc(50%-6px)] lg:basis-auto">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.steps.th.timing')}</label>
            <input type="text" name="stepSets[0].steps[0].timing" required class="dense-input" placeholder="${I18n.t('figures.view.steps.th.timing')}" />
        </div>
        <div class="min-w-0 basis-[calc(50%-6px)] lg:basis-auto">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.steps.th.foot')}</label>
            <input type="text" name="stepSets[0].steps[0].foot" required class="dense-input" placeholder="${I18n.t('figures.view.steps.th.foot')}" />
        </div>
        <div class="min-w-0 basis-full lg:basis-auto">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.steps.th.action')}</label>
            <input type="text" name="stepSets[0].steps[0].action" required class="dense-input" placeholder="${I18n.t('figures.view.steps.th.action')}" />
        </div>
        <div class="min-w-0 basis-[calc(50%-6px)] lg:basis-auto">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.steps.th.footwork')}</label>
            <input type="text" name="stepSets[0].steps[0].footwork" class="dense-input" placeholder="${I18n.t('figures.form.steps.th.footwork')}" />
        </div>
        <div class="min-w-0 basis-[calc(50%-6px)] lg:basis-auto">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.steps.th.alignment')}</label>
            <input type="text" name="stepSets[0].steps[0].alignment" class="dense-input" placeholder="${I18n.t('figures.form.steps.th.alignment')}" />
        </div>
        <div class="min-w-0 basis-full lg:basis-auto">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.steps.th.turn')}</label>
            <input type="text" name="stepSets[0].steps[0].amountOfTurn" class="dense-input" placeholder="${I18n.t('figures.view.steps.th.turn')}" />
        </div>
        <div class="min-w-0 basis-full lg:basis-auto">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.steps.th.comments')}</label>
            <textarea name="stepSets[0].steps[0].commentsText" class="dense-textarea" rows="2" placeholder="${I18n.t('figures.form.steps.th.comments')}"></textarea>
        </div>
    `;

    tbody.appendChild(tr);
    reindexStepSets();

    const activeRoleBtn = panel.querySelector('.js-view-role-btn.active');
    const curRole = activeRoleBtn ? activeRoleBtn.getAttribute('data-role') : role;
    switchRoleView(panel, curRole);
}

function removeStepRow(button) {
    const row = button.closest('.step-row');
    if (row) {
        row.remove();
        reindexStepSets();
    }
}

// -------------------------------------------------------------
// Flow Figures Functions (Chips & Multi-Select Sync)
// -------------------------------------------------------------
function addFlowFigure(flowType, name) {
    const hiddenSelect = document.getElementById(flowType === 'preceding' ? 'precedingFigureNames' : 'followingFigureNames');
    const chipsList = document.querySelector(`.js-chips-list[data-flow="${flowType}"]`);
    if (!hiddenSelect || !chipsList) return;

    // Check if figure already selected
    let opt = Array.from(hiddenSelect.options).find(o => o.value === name);
    if (opt && opt.selected) return;

    if (!opt) {
        opt = document.createElement('option');
        opt.value = name;
        opt.textContent = name;
        hiddenSelect.appendChild(opt);
    }
    opt.selected = true;

    // Hide None yet
    const noneEl = chipsList.querySelector('.js-flow-none');
    if (noneEl) noneEl.classList.add('hidden');

    // Create chip element
    const chip = document.createElement('span');
    chip.className = 'inline-flex items-center gap-1.5 min-h-[36px] pl-3.5 pr-1.5 rounded-full bg-surface-container border border-border font-semibold text-[13px] js-flow-chip';
    chip.setAttribute('data-fig', name);
    chip.innerHTML = `
        <span>${name}</span>
        <button type="button" class="w-6 h-6 rounded-full hover:bg-surface-container-highest flex items-center justify-center text-on-surface-variant js-remove-flow-chip" aria-label="${I18n.t('figures.form.flow.remove_figure')}">
            ${renderIcon('close', { size: 'sm' })}
        </button>
    `;
    chipsList.appendChild(chip);
}

function removeFlowFigure(flowType, name, chipElement) {
    const hiddenSelect = document.getElementById(flowType === 'preceding' ? 'precedingFigureNames' : 'followingFigureNames');
    const chipsList = document.querySelector(`.js-chips-list[data-flow="${flowType}"]`);
    if (!hiddenSelect || !chipsList) return;

    // Unselect from hidden select
    const opt = Array.from(hiddenSelect.options).find(o => o.value === name);
    if (opt) opt.selected = false;

    if (chipElement) chipElement.remove();

    // Check if any chips remain
    const remainingChips = chipsList.querySelectorAll('.js-flow-chip');
    if (remainingChips.length === 0) {
        const noneEl = chipsList.querySelector('.js-flow-none');
        if (noneEl) noneEl.classList.remove('hidden');
    }
}

function updateFlowDropdowns(danceTypeId) {
    const preSelect = document.querySelector('.js-flow-add-select[data-flow="preceding"]');
    const folSelect = document.querySelector('.js-flow-add-select[data-flow="following"]');
    if (!preSelect || !folSelect || !danceTypeId) return;

    fetch('/dance-figures/api?danceTypeId=' + danceTypeId)
        .then(res => res.json())
        .then(data => {
            const currentName = document.getElementById('name')?.value || '';
            const buildOptions = () => {
                let html = `<option value="">${I18n.t('figures.form.flow.add_placeholder')}</option>`;
                data.forEach(fig => {
                    if (fig.name !== currentName) {
                        html += `<option value="${fig.name}">${fig.name}</option>`;
                    }
                });
                return html;
            };

            preSelect.innerHTML = buildOptions();
            folSelect.innerHTML = buildOptions();
        })
        .catch(err => console.error("Could not fetch figures for dance style mapping", err));
}

// -------------------------------------------------------------
// Links Management Functions
// -------------------------------------------------------------
function reindexLinks() {
    const linkRows = document.querySelectorAll('.link-row');
    linkRows.forEach((row, idx) => {
        row.querySelectorAll('input, select').forEach(input => {
            const name = input.name;
            if (name) {
                input.name = name.replace(/links\[\d+\]/, 'links[' + idx + ']');
            }
        });
    });
}

function addLinkRow() {
    const tbody = document.getElementById('links-tbody');
    if (!tbody) return;

    const totalIndex = document.querySelectorAll('.link-row').length;
    const tr = document.createElement('div');
    tr.className = 'link-row py-3 lg:py-1.5 grid grid-cols-[minmax(0,1fr)_36px] lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_150px_36px] gap-2 items-center border-b border-border';

    tr.innerHTML = `
        <div class="col-span-1 min-w-0">
            <input type="hidden" name="links[${totalIndex}].id" value="" />
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.links.th.title')}</label>
            <input type="text" name="links[${totalIndex}].title" class="dense-input" placeholder="${I18n.t('figures.form.links.title_placeholder')}" />
        </div>
        <div class="col-span-full lg:col-span-1 min-w-0">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.links.th.url')}</label>
            <input type="text" name="links[${totalIndex}].url" required class="dense-input" placeholder="https://" />
        </div>
        <div class="col-span-full lg:col-span-1 min-w-0">
            <label class="lg:hidden block text-xs font-semibold text-on-surface-variant mb-1">${I18n.t('figures.form.links.th.type')}</label>
            <select name="links[${totalIndex}].type" class="dense-select">
                <option value="video">${I18n.t('figures.form.links.type.video')}</option>
                <option value="syllabus">${I18n.t('figures.form.links.type.syllabus')}</option>
                <option value="other">${I18n.t('figures.form.links.type.other')}</option>
            </select>
        </div>
        <div class="col-start-2 row-start-1 lg:col-auto lg:row-auto flex justify-end lg:justify-center">
            <button type="button" aria-label="${I18n.t('figures.form.links.remove')}" class="btn-icon text-on-surface-variant hover:text-danger js-remove-link-btn">
                ${renderIcon('delete', { size: 'sm' })}
            </button>
        </div>
    `;

    tbody.appendChild(tr);
    reindexLinks();
}

function removeLinkRow(button) {
    const row = button.closest('.link-row');
    if (row) {
        row.remove();
        reindexLinks();
    }
}
