document.addEventListener('DOMContentLoaded', () => {
    // Step Sets Tab Switching
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

    // Add Step Set button
    const addStepSetBtn = document.getElementById('add-step-set-btn');
    if (addStepSetBtn) {
        addStepSetBtn.addEventListener('click', addStepSet);
    }

    // Event delegation for operations inside Step Sets Panels
    const panelsContainer = document.getElementById('step-sets-panels-container');
    if (panelsContainer) {
        // Remove Step Set
        panelsContainer.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-remove-step-set-btn');
            if (btn) {
                removeStepSet(btn);
            }
        });

        // Set Default Radio Selection change
        panelsContainer.addEventListener('change', (e) => {
            const radio = e.target.closest('.js-set-default-radio');
            if (radio) {
                updateDefaultSetSelection(radio.value);
            }
        });

        // Synchronization of combination name text inputs to tab titles
        panelsContainer.addEventListener('input', (e) => {
            const nameInput = e.target.closest('.js-set-name-input');
            if (nameInput) {
                const panel = nameInput.closest('.js-step-set-panel');
                const idx = panel.getAttribute('data-set-idx');
                const tab = document.querySelector(`.js-step-set-tab[data-set-idx="${idx}"]`);
                if (tab) {
                    const display = tab.querySelector('.js-tab-name-display');
                    if (display) {
                        display.textContent = nameInput.value.trim() || `Set ${parseInt(idx) + 1}`;
                    }
                }
            }
        });

        // Role Tab Switching inside panel
        panelsContainer.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-panel-role-btn');
            if (btn) {
                const panel = btn.closest('.js-step-set-panel');
                const role = btn.getAttribute('data-role');
                switchPanelRole(panel, role);
            }
        });

        // Add Step Row inside panel
        panelsContainer.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-add-step-btn');
            if (btn) {
                const panel = btn.closest('.js-step-set-panel');
                const role = btn.getAttribute('data-role');
                addStepRow(panel, role);
            }
        });

        // Remove Step Row inside panel
        panelsContainer.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-remove-step-btn');
            if (btn) {
                removeStepRow(btn);
            }
        });
    }

    // Links list management
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

    // Dance style change mapping
    const danceTypeSelect = document.getElementById('danceTypeId');
    if (danceTypeSelect) {
        danceTypeSelect.addEventListener('change', (e) => {
            const danceTypeId = e.target.value;
            const precedingSelect = document.getElementById('precedingFigureNames');
            const followingSelect = document.getElementById('followingFigureNames');
            
            if (!precedingSelect || !followingSelect) return;

            // Clear options
            precedingSelect.innerHTML = '';
            followingSelect.innerHTML = '';
            
            if (!danceTypeId) return;
            
            // Query dance figures via AJAX
            fetch('/dance-figures/api?danceTypeId=' + danceTypeId)
                .then(response => response.json())
                .then(data => {
                    data.forEach(fig => {
                        const nameField = document.getElementById('name');
                        const currentFigureName = nameField ? nameField.value : '';
                        if (fig.name === currentFigureName) return;
                        
                        const opt1 = document.createElement('option');
                        opt1.value = fig.name;
                        opt1.textContent = fig.name;
                        precedingSelect.appendChild(opt1);
                        
                        const opt2 = document.createElement('option');
                        opt2.value = fig.name;
                        opt2.textContent = fig.name;
                        followingSelect.appendChild(opt2);
                    });
                })
                .catch(err => console.error("Could not fetch figures for dance style mapping", err));
        });
    }

    // Toggle Mobile manual steps collapsible details
    document.addEventListener('click', (e) => {
        const toggleBtn = e.target.closest('.js-toggle-mobile-fields');
        if (toggleBtn) {
            const row = toggleBtn.closest('.step-row');
            if (row) {
                const collapsedFields = row.querySelectorAll('.js-mobile-collapsed');
                const isHidden = collapsedFields[0].classList.contains('hidden');
                collapsedFields.forEach(field => {
                    if (isHidden) {
                        field.classList.remove('hidden');
                    } else {
                        field.classList.add('hidden');
                    }
                });
                
                const arrow = toggleBtn.querySelector('.js-mobile-arrow');
                const label = toggleBtn.querySelector('span');
                if (arrow && label) {
                    if (isHidden) {
                        arrow.classList.add('rotate-180');
                        label.textContent = 'Hide Technical Details';
                    } else {
                        arrow.classList.remove('rotate-180');
                        label.textContent = 'Show Technical Details';
                    }
                }
            }
        }
    });

    // Make sure we correctly initialize indicators on load
    const checkedRadio = document.querySelector('.js-set-default-radio:checked');
    if (checkedRadio) {
        updateDefaultSetSelection(checkedRadio.value);
    }
});

function setActiveStepSet(index) {
    // Toggle active classes on tabs
    document.querySelectorAll('.js-step-set-tab').forEach(tab => {
        const idx = tab.getAttribute('data-set-idx');
        if (idx === index) {
            tab.className = 'js-step-set-tab flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm font-semibold cursor-pointer transition-all shrink-0 bg-primary border-primary text-white';
        } else {
            tab.className = 'js-step-set-tab flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm font-semibold cursor-pointer transition-all shrink-0 bg-surface border-border text-text-secondary hover:text-on-surface';
        }
    });

    // Toggle visibility of panels
    document.querySelectorAll('.js-step-set-panel').forEach(panel => {
        const idx = panel.getAttribute('data-set-idx');
        if (idx === index) {
            panel.classList.remove('hidden');
        } else {
            panel.classList.add('hidden');
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

        // Update tab indicators
        const tab = document.querySelector(`.js-step-set-tab[data-set-idx="${idx}"]`);
        if (tab) {
            let indicator = tab.querySelector('.js-tab-default-indicator');
            if (isDefault) {
                if (!indicator) {
                    indicator = document.createElement('span');
                    indicator.className = 'js-tab-default-indicator material-symbols-outlined text-[14px]';
                    indicator.textContent = 'star';
                    tab.appendChild(indicator);
                }
            } else {
                if (indicator) indicator.remove();
            }
        }
    });
}

function switchPanelRole(panel, role) {
    const isLeader = role === 'LEADER';
    
    panel.querySelectorAll('.js-panel-role-btn').forEach(btn => {
        const btnRole = btn.getAttribute('data-role');
        if (btnRole === role) {
            btn.className = 'js-panel-role-btn active-role-btn flex items-center gap-1.5 px-4 py-1.5 rounded-md font-bold transition-all bg-primary-container text-on-primary-container shadow-xs';
        } else {
            btn.className = 'js-panel-role-btn flex items-center gap-1.5 px-4 py-1.5 rounded-md font-semibold transition-all text-text-secondary hover:text-on-surface';
        }
    });

    const leaderSec = panel.querySelector('.js-leader-steps-section');
    const followerSec = panel.querySelector('.js-follower-steps-section');

    if (isLeader) {
        leaderSec.classList.remove('hidden');
        followerSec.classList.add('hidden');
    } else {
        leaderSec.classList.add('hidden');
        followerSec.classList.remove('hidden');
    }
}

function reindexStepSets() {
    const panels = document.querySelectorAll('.js-step-set-panel');
    const tabs = document.querySelectorAll('.js-step-set-tab');

    panels.forEach((panel, setIdx) => {
        panel.setAttribute('data-set-idx', setIdx);
        
        // Update name indices
        panel.querySelectorAll('.step-set-id').forEach(el => el.name = `stepSets[${setIdx}].id`);
        
        const nameInput = panel.querySelector('.js-set-name-input');
        if (nameInput) nameInput.name = `stepSets[${setIdx}].name`;
        
        const radio = panel.querySelector('.js-set-default-radio');
        if (radio) {
            radio.id = `set-default-radio-${setIdx}`;
            radio.value = setIdx;
            const label = panel.querySelector('label[for^="set-default-radio"]');
            if (label) label.setAttribute('for', `set-default-radio-${setIdx}`);
        }
        
        const defaultHidden = panel.querySelector('.js-set-default-hidden');
        if (defaultHidden) defaultHidden.name = `stepSets[${setIdx}].isDefault`;

        // Reindex step numbers for leader/follower displays
        const leaderRows = panel.querySelectorAll('.js-leader-steps-tbody .step-row');
        leaderRows.forEach((row, i) => {
            row.querySelector('.step-number-display').textContent = i + 1;
        });

        const followerRows = panel.querySelectorAll('.js-follower-steps-tbody .step-row');
        followerRows.forEach((row, i) => {
            row.querySelector('.step-number-display').textContent = i + 1;
        });

        // Reindex all input names for steps under this set
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
    const addSetBtn = document.getElementById('add-step-set-btn');

    if (!panelsContainer || !tabsContainer || !addSetBtn) return;

    const setIdx = document.querySelectorAll('.js-step-set-panel').length;

    // Prompt user for new combination name (pre-populated with Default)
    const rawName = prompt("Enter a name for the new step combination:", `Combination ${setIdx + 1}`);
    const setName = rawName ? rawName.trim() : "";
    if (setName === "") return;

    // Create tab
    const tab = document.createElement('div');
    tab.className = 'js-step-set-tab flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm font-semibold cursor-pointer transition-all shrink-0 bg-surface border-border text-text-secondary hover:text-on-surface';
    tab.setAttribute('data-set-idx', setIdx);
    tab.innerHTML = `
        <span class="js-tab-name-display">${setName}</span>
    `;
    tabsContainer.insertBefore(tab, addSetBtn);

    // Create panel
    const panel = document.createElement('div');
    panel.className = 'js-step-set-panel space-y-6 hidden';
    panel.setAttribute('data-set-idx', setIdx);
    panel.innerHTML = `
        <!-- Step Set Header / Metadata -->
        <div class="grid grid-cols-1 md:grid-cols-3 gap-4 bg-surface-container-low p-4 rounded-lg border border-border/40">
            <input type="hidden" class="step-set-id" name="stepSets[${setIdx}].id" value="" />
            
            <div>
                <label class="block text-xs font-bold text-outline uppercase mb-1">Combination Name *</label>
                <input type="text" name="stepSets[${setIdx}].name" value="${setName}" required 
                       class="form-input text-sm py-2 js-set-name-input" placeholder="e.g., Default, Alternative Timing" />
            </div>
            
            <div class="flex items-center gap-2 pt-5">
                <input type="radio" id="set-default-radio-${setIdx}" name="default-set-radio" 
                       value="${setIdx}" class="form-radio js-set-default-radio" />
                <input type="hidden" class="js-set-default-hidden" name="stepSets[${setIdx}].isDefault" value="false" />
                <label for="set-default-radio-${setIdx}" class="text-sm font-semibold text-on-surface cursor-pointer select-none">
                    Mark as Default
                </label>
            </div>
            
            <div class="flex items-center justify-end pt-4">
                <button type="button" class="btn-outline btn-sm text-danger hover:bg-danger-soft/20 border-danger/30 hover:border-danger js-remove-step-set-btn">
                    <span class="material-symbols-outlined text-[16px] mr-1">delete</span> Delete Combination
                </button>
            </div>
        </div>

        <!-- Role Tab Selector inside set panel -->
        <div class="flex items-center justify-between border-b border-border/40 pb-2">
            <span class="text-sm font-bold text-outline uppercase">Steps List</span>
            <div class="inline-flex bg-surface-container-high p-0.5 rounded-lg border border-border text-sm">
                <button type="button" class="js-panel-role-btn active-role-btn flex items-center gap-1.5 px-4 py-1.5 rounded-md font-bold transition-all bg-primary-container text-on-primary-container shadow-xs" data-role="LEADER">
                    <span class="material-symbols-outlined text-[18px]">face</span> Leader
                </button>
                <button type="button" class="js-panel-role-btn flex items-center gap-1.5 px-4 py-1.5 rounded-md font-semibold transition-all text-text-secondary hover:text-on-surface" data-role="FOLLOWER">
                    <span class="material-symbols-outlined text-[18px]">face_2</span> Follower
                </button>
            </div>
        </div>

        <!-- LEADER STEPS TABLE -->
        <div class="js-leader-steps-section space-y-4">
            <div class="overflow-x-auto min-h-[120px]">
                <table class="w-full text-left border-collapse">
                    <thead class="hidden md:table-header-group">
                        <tr class="border-b border-border text-[11px] font-semibold uppercase tracking-wider text-outline">
                            <th class="py-2 pr-2 w-12">#</th>
                            <th class="py-2 pr-2 w-20">Timing *</th>
                            <th class="py-2 pr-2 w-20">Foot *</th>
                            <th class="py-2 pr-2 min-w-[150px]">Action *</th>
                            <th class="py-2 pr-2 w-24">Footwork</th>
                            <th class="py-2 pr-2 w-24">Alignment</th>
                            <th class="py-2 pr-2 w-24">Turn</th>
                            <th class="py-2 pr-2 min-w-[150px]">Comments</th>
                            <th class="py-2 w-10 text-right"></th>
                        </tr>
                    </thead>
                    <tbody class="js-leader-steps-tbody">
                    </tbody>
                </table>
            </div>
            <button type="button" class="btn-outline btn-sm js-add-step-btn" data-role="LEADER">
                <span class="material-symbols-outlined text-[16px]">add</span> Add Leader Step
            </button>
        </div>

        <!-- FOLLOWER STEPS TABLE -->
        <div class="js-follower-steps-section space-y-4 hidden">
            <div class="overflow-x-auto min-h-[120px]">
                <table class="w-full text-left border-collapse">
                    <thead class="hidden md:table-header-group">
                        <tr class="border-b border-border text-[11px] font-semibold uppercase tracking-wider text-outline">
                            <th class="py-2 pr-2 w-12">#</th>
                            <th class="py-2 pr-2 w-20">Timing *</th>
                            <th class="py-2 pr-2 w-20">Foot *</th>
                            <th class="py-2 pr-2 min-w-[150px]">Action *</th>
                            <th class="py-2 pr-2 w-24">Footwork</th>
                            <th class="py-2 pr-2 w-24">Alignment</th>
                            <th class="py-2 pr-2 w-24">Turn</th>
                            <th class="py-2 pr-2 min-w-[150px]">Comments</th>
                            <th class="py-2 w-10 text-right"></th>
                        </tr>
                    </thead>
                    <tbody class="js-follower-steps-tbody">
                    </tbody>
                </table>
            </div>
            <button type="button" class="btn-outline btn-sm js-add-step-btn" data-role="FOLLOWER">
                <span class="material-symbols-outlined text-[16px]">add</span> Add Follower Step
            </button>
        </div>
    `;
    panelsContainer.appendChild(panel);

    // If this is the only step set, mark it default automatically
    if (setIdx === 0) {
        updateDefaultSetSelection("0");
    }

    reindexStepSets();
    setActiveStepSet(setIdx.toString());
}

function removeStepSet(button) {
    const panels = document.querySelectorAll('.js-step-set-panel');
    if (panels.length <= 1) {
        alert("At least one step combination is required.");
        return;
    }

    if (!confirm("Are you sure you want to delete this step combination and all of its steps?")) {
        return;
    }

    const panel = button.closest('.js-step-set-panel');
    const idxToRemove = parseInt(panel.getAttribute('data-set-idx'));
    const tabToRemove = document.querySelector(`.js-step-set-tab[data-set-idx="${idxToRemove}"]`);

    const wasDefault = panel.querySelector('.js-set-default-radio').checked;

    panel.remove();
    if (tabToRemove) tabToRemove.remove();

    // If default was deleted, mark first remaining set as default
    if (wasDefault) {
        updateDefaultSetSelection("0");
    }

    reindexStepSets();

    // Select the first remaining step set
    setActiveStepSet("0");
}

function addStepRow(panel, role) {
    const tbody = panel.querySelector(`.js-${role.toLowerCase()}-steps-tbody`);
    if (!tbody) return;

    const tr = document.createElement('tr');
    tr.className = 'grid grid-cols-2 md:table-row gap-3 p-4 md:p-2 bg-white border border-border md:border-b md:border-0 rounded-lg md:rounded-none shadow-xs md:shadow-none mb-4 md:mb-0 relative step-row animate-fadeIn';
    tr.setAttribute('data-role', role);
    
    // Default placeholders inside names; reindexStepSets will replace them
    tr.innerHTML = `
        <td class="col-span-2 md:table-cell py-1 md:py-2 flex items-center justify-between border-b border-border/30 pb-2 md:border-b-0 md:pb-0">
            <input type="hidden" class="step-id" name="stepSets[0].steps[0].id" value="" />
            <input type="hidden" class="step-role" name="stepSets[0].steps[0].role" value="${role}" />
            <span class="step-number-display text-sm font-bold text-primary md:text-xs md:font-semibold md:text-outline">1</span>
            <button type="button" class="btn-icon text-danger hover:bg-danger-soft/20 js-remove-step-btn md:hidden">
                <span class="material-symbols-outlined text-[18px]">delete</span>
            </button>
        </td>
        <td class="col-span-1 md:table-cell py-1 md:py-2">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Timing *</label>
            <input type="text" name="stepSets[0].steps[0].timing" required class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-1 md:table-cell py-1 md:py-2">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Foot *</label>
            <input type="text" name="stepSets[0].steps[0].foot" required class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Action Description *</label>
            <input type="text" name="stepSets[0].steps[0].action" required class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Footwork</label>
            <input type="text" name="stepSets[0].steps[0].footwork" class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Alignment</label>
            <input type="text" name="stepSets[0].steps[0].alignment" class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Turn</label>
            <input type="text" name="stepSets[0].steps[0].amountOfTurn" class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Technical Comments</label>
            <textarea name="stepSets[0].steps[0].commentsText" class="form-textarea text-xs min-h-[38px] py-1.5" rows="1"></textarea>
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 text-right flex md:block items-center justify-between gap-2 border-t border-border/30 pt-2 md:border-t-0 md:pt-0">
            <button type="button" class="text-xs text-primary font-medium flex items-center gap-0.5 md:hidden js-toggle-mobile-fields">
                <span>Show Technical Details</span>
                <span class="material-symbols-outlined text-[16px] js-mobile-arrow">expand_more</span>
            </button>
            <button type="button" class="btn-icon text-danger hover:bg-danger-soft/20 js-remove-step-btn hidden md:inline-flex">
                <span class="material-symbols-outlined text-[18px]">delete</span>
            </button>
        </td>
    `;
    tbody.appendChild(tr);
    reindexStepSets();
}

function removeStepRow(button) {
    const row = button.closest('.step-row');
    if (row) {
        row.remove();
        reindexStepSets();
    }
}

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
    
    const tr = document.createElement('tr');
    tr.className = 'link-row border-b border-border';
    
    tr.innerHTML = `
        <td class="py-2 pr-2">
            <input type="hidden" name="links[${totalIndex}].id" value="" />
            <input type="text" name="links[${totalIndex}].title" class="form-input text-xs py-1.5" placeholder="e.g. Routine Video Tutorial" />
        </td>
        <td class="py-2 pr-2">
            <input type="text" name="links[${totalIndex}].url" required class="form-input text-xs py-1.5" placeholder="https://..." />
        </td>
        <td class="py-2 pr-2">
            <select name="links[${totalIndex}].type" class="form-select text-xs py-1.5 pr-8">
                <option value="video">Video</option>
                <option value="syllabus">Syllabus</option>
                <option value="other">Other</option>
            </select>
        </td>
        <td class="py-2 text-right">
            <button type="button" class="btn-icon text-danger hover:bg-danger-soft/20 js-remove-link-btn">
                <span class="material-symbols-outlined text-[18px]">delete</span>
            </button>
        </td>
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
