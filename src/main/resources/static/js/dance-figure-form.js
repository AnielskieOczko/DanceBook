document.addEventListener('DOMContentLoaded', () => {
    // Tab switching
    const leaderBtn = document.getElementById('leader-tab-btn');
    const followerBtn = document.getElementById('follower-tab-btn');
    if (leaderBtn && followerBtn) {
        leaderBtn.addEventListener('click', () => switchRoleTab('LEADER'));
        followerBtn.addEventListener('click', () => switchRoleTab('FOLLOWER'));
    }

    // Add row buttons
    const addLeaderStepBtn = document.getElementById('add-leader-step-btn');
    const addFollowerStepBtn = document.getElementById('add-follower-step-btn');
    const addLinkBtn = document.getElementById('add-link-btn');

    if (addLeaderStepBtn) {
        addLeaderStepBtn.addEventListener('click', () => addStepRow('LEADER'));
    }
    if (addFollowerStepBtn) {
        addFollowerStepBtn.addEventListener('click', () => addStepRow('FOLLOWER'));
    }
    if (addLinkBtn) {
        addLinkBtn.addEventListener('click', addLinkRow);
    }

    // Delete row event delegation
    const leaderTbody = document.getElementById('leader-steps-tbody');
    const followerTbody = document.getElementById('follower-steps-tbody');
    const linksTbody = document.getElementById('links-tbody');

    if (leaderTbody) {
        leaderTbody.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-remove-step-btn');
            if (btn) removeStepRow(btn);
        });
    }
    if (followerTbody) {
        followerTbody.addEventListener('click', (e) => {
            const btn = e.target.closest('.js-remove-step-btn');
            if (btn) removeStepRow(btn);
        });
    }
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
                        // Skip ourselves if we are in edit mode
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
});

function switchRoleTab(role) {
    const isLeader = role === 'LEADER';
    
    const leaderBtn = document.getElementById('leader-tab-btn');
    const followerBtn = document.getElementById('follower-tab-btn');
    const leaderSec = document.getElementById('leader-steps-section');
    const followerSec = document.getElementById('follower-steps-section');

    if (!leaderBtn || !followerBtn || !leaderSec || !followerSec) return;

    // Toggle active styling classes on buttons
    leaderBtn.className = isLeader 
        ? 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-primary-container text-primary-container'
        : 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-transparent text-text-secondary hover:text-on-surface';
    followerBtn.className = !isLeader 
        ? 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-primary-container text-primary-container'
        : 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-transparent text-text-secondary hover:text-on-surface';

    // Toggle visibility of step tables
    if (isLeader) {
        leaderSec.classList.remove('hidden');
        followerSec.classList.add('hidden');
    } else {
        leaderSec.classList.add('hidden');
        followerSec.classList.remove('hidden');
    }
}

function reindexSteps() {
    // Visual step number reindexing for Leader
    const leaderRows = document.querySelectorAll('#leader-steps-tbody .step-row');
    leaderRows.forEach((row, i) => {
        row.querySelector('.step-number-display').textContent = i + 1;
    });

    // Visual step number reindexing for Follower
    const followerRows = document.querySelectorAll('#follower-steps-tbody .step-row');
    followerRows.forEach((row, i) => {
        row.querySelector('.step-number-display').textContent = i + 1;
    });

    // Re-assign request model indices consecutively: steps[N].fieldName
    const allRows = document.querySelectorAll('.step-row');
    allRows.forEach((row, idx) => {
        row.querySelectorAll('input, select, textarea').forEach(input => {
            const name = input.name;
            if (name) {
                input.name = name.replace(/steps\[\d+\]/, 'steps[' + idx + ']');
            }
        });
    });
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

function addStepRow(role) {
    const tbody = document.getElementById(role.toLowerCase() + '-steps-tbody');
    if (!tbody) return;

    const totalIndex = document.querySelectorAll('.step-row').length;
    
    const tr = document.createElement('tr');
    tr.className = 'grid grid-cols-2 md:table-row gap-3 p-4 md:p-2 bg-white border border-border md:border-b md:border-0 rounded-lg md:rounded-none shadow-sm md:shadow-none mb-4 md:mb-0 relative step-row';
    tr.setAttribute('data-role', role);
    
    tr.innerHTML = `
        <td class="col-span-2 md:table-cell py-1 md:py-2 flex items-center justify-between border-b border-border/30 pb-2 md:border-b-0 md:pb-0">
            <input type="hidden" class="step-id" name="steps[${totalIndex}].id" value="" />
            <input type="hidden" class="step-role" name="steps[${totalIndex}].role" value="${role}" />
            <span class="step-number-display text-sm font-bold text-primary md:text-xs md:font-semibold md:text-outline before:content-['Step_'] md:before:content-none">1</span>
            <!-- Delete button on mobile -->
            <button type="button" class="btn-icon text-danger hover:bg-danger-soft/20 js-remove-step-btn md:hidden">
                <span class="material-symbols-outlined text-[18px]">delete</span>
            </button>
        </td>
        <td class="col-span-1 md:table-cell py-1 md:py-2">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Timing *</label>
            <input type="text" name="steps[${totalIndex}].timing" required class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-1 md:table-cell py-1 md:py-2">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Foot *</label>
            <input type="text" name="steps[${totalIndex}].foot" required class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Action Description *</label>
            <input type="text" name="steps[${totalIndex}].action" required class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Footwork</label>
            <input type="text" name="steps[${totalIndex}].footwork" class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Alignment</label>
            <input type="text" name="steps[${totalIndex}].alignment" class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Turn</label>
            <input type="text" name="steps[${totalIndex}].amountOfTurn" class="form-input text-xs py-1.5" />
        </td>
        <td class="col-span-2 md:table-cell py-1 md:py-2 js-mobile-collapsed hidden md:table-cell">
            <label class="block md:hidden text-[10px] font-semibold text-outline uppercase mb-1">Technical Comments</label>
            <textarea name="steps[${totalIndex}].commentsText" class="form-textarea text-xs min-h-[38px] py-1.5" rows="1"></textarea>
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
    reindexSteps();
}

function removeStepRow(button) {
    const row = button.closest('.step-row');
    if (row) {
        row.remove();
        reindexSteps();
    }
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
