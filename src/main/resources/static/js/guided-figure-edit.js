document.addEventListener('DOMContentLoaded', () => {
    // 1. Elements
    const manualEditTabBtn = document.getElementById('manual-edit-tab-btn');
    const guidedEditTabBtn = document.getElementById('guided-edit-tab-btn');
    const guidedEditPanel = document.getElementById('guided-edit-panel');
    const figureForm = document.getElementById('figureForm');

    const importJsonTabBtn = document.getElementById('import-json-tab-btn');
    const importUrlTabBtn = document.getElementById('import-url-tab-btn');
    const jsonInputSection = document.getElementById('json-input-section');
    const urlInputSection = document.getElementById('url-input-section');

    const guidedJsonInput = document.getElementById('guided-json-input');
    const toggleSchemaBtn = document.getElementById('toggle-schema-btn');
    const expectedSchemaBox = document.getElementById('expected-schema-box');
    const schemaDisplayPre = document.getElementById('schema-display-pre');
    const validateJsonBtn = document.getElementById('validate-json-btn');

    const guidedUrlInput = document.getElementById('guided-url-input');
    const guidedModelSelect = document.getElementById('guided-model-select');
    const urlHistoryContainer = document.getElementById('url-history-container');
    const guidedUrlHistory = document.getElementById('guided-url-history');
    const parseUrlBtn = document.getElementById('parse-url-btn');

    // Advanced setting inputs
    const guidedMaxTokens = document.getElementById('guided-max-tokens');
    const guidedTemperature = document.getElementById('guided-temperature');
    const guidedReasoningEffort = document.getElementById('guided-reasoning-effort');

    const importLoadingState = document.getElementById('import-loading-state');
    const diffComparisonSection = document.getElementById('diff-comparison-section');
    const diffTableBody = document.getElementById('diff-table-body');
    const discardImportBtn = document.getElementById('discard-import-btn');
    const applyImportBtn = document.getElementById('apply-import-btn');

    const importSectionMetadata = document.getElementById('import-section-metadata');
    const importSectionSteps = document.getElementById('import-section-steps');
    const importSectionLinks = document.getElementById('import-section-links');

    const diffMobileTabCurrent = document.getElementById('diff-mobile-tab-current');
    const diffMobileTabImported = document.getElementById('diff-mobile-tab-imported');

    // Stats and Errors elements
    const guidedErrorContainer = document.getElementById('guided-error-container');
    const guidedErrorMessage = document.getElementById('guided-error-message');
    const tokenStatsContainer = document.getElementById('token-stats-container');
    const statPromptTokens = document.getElementById('stat-prompt-tokens');
    const statCompletionTokens = document.getElementById('stat-completion-tokens');
    const statReasoningTokens = document.getElementById('stat-reasoning-tokens');
    const statTotalTokens = document.getElementById('stat-total-tokens');

    if (!guidedEditPanel || !figureForm) return;

    let parsedResultData = null; // Stores currently loaded parsed data
    const isEditPage = checkIsEditPage();

    // 2. Tab Toggling (Manual vs Guided)
    manualEditTabBtn.addEventListener('click', () => {
        switchMainTab('MANUAL');
    });

    guidedEditTabBtn.addEventListener('click', () => {
        switchMainTab('GUIDED');
    });

    function switchMainTab(tab) {
        if (tab === 'MANUAL') {
            figureForm.classList.remove('hidden');
            guidedEditPanel.classList.add('hidden');
            
            manualEditTabBtn.className = 'px-4 py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
            guidedEditTabBtn.className = 'px-4 py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
        } else {
            figureForm.classList.add('hidden');
            guidedEditPanel.classList.remove('hidden');
            
            manualEditTabBtn.className = 'px-4 py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
            guidedEditTabBtn.className = 'px-4 py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
            
            // Auto-load models and history if not loaded
            loadModels();
            loadUrlHistory();
        }
    }

    // Keyboard shortcut (Ctrl+Shift+G)
    window.addEventListener('keydown', (e) => {
        if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'g') {
            e.preventDefault();
            if (guidedEditPanel.classList.contains('hidden')) {
                switchMainTab('GUIDED');
            } else {
                switchMainTab('MANUAL');
            }
        }
    });

    // Collapsible Steps Preview Toggle
    const toggleDiffStepsBtn = document.getElementById('toggle-diff-steps-btn');
    const diffStepsPreviewContent = document.getElementById('diff-steps-preview-content');
    const diffStepsArrow = document.getElementById('diff-steps-arrow');
    if (toggleDiffStepsBtn && diffStepsPreviewContent && diffStepsArrow) {
        toggleDiffStepsBtn.addEventListener('click', (e) => {
            e.preventDefault();
            const isHidden = diffStepsPreviewContent.classList.contains('hidden');
            if (isHidden) {
                diffStepsPreviewContent.classList.remove('hidden');
                diffStepsArrow.classList.add('rotate-180');
            } else {
                diffStepsPreviewContent.classList.add('hidden');
                diffStepsArrow.classList.remove('rotate-180');
            }
        });
    }

    // Collapsible Links Preview Toggle
    const toggleDiffLinksBtn = document.getElementById('toggle-diff-links-btn');
    const diffLinksPreviewContent = document.getElementById('diff-links-preview-content');
    const diffLinksArrow = document.getElementById('diff-links-arrow');
    if (toggleDiffLinksBtn && diffLinksPreviewContent && diffLinksArrow) {
        toggleDiffLinksBtn.addEventListener('click', (e) => {
            e.preventDefault();
            const isHidden = diffLinksPreviewContent.classList.contains('hidden');
            if (isHidden) {
                diffLinksPreviewContent.classList.remove('hidden');
                diffLinksArrow.classList.add('rotate-180');
            } else {
                diffLinksPreviewContent.classList.add('hidden');
                diffLinksArrow.classList.remove('rotate-180');
            }
        });
    }
    // Mobile Diff Tab Switcher Toggle
    if (diffMobileTabCurrent && diffMobileTabImported) {
        diffMobileTabCurrent.addEventListener('click', (e) => {
            e.preventDefault();
            switchDiffMobileTab('CURRENT');
        });

        diffMobileTabImported.addEventListener('click', (e) => {
            e.preventDefault();
            switchDiffMobileTab('IMPORTED');
        });
    }

    function switchDiffMobileTab(tab) {
        const currentCols = document.querySelectorAll('.js-col-current');
        const importedCols = document.querySelectorAll('.js-col-imported');
        
        if (tab === 'CURRENT') {
            currentCols.forEach(col => col.classList.remove('max-md:hidden'));
            importedCols.forEach(col => col.classList.add('max-md:hidden'));
            
            diffMobileTabCurrent.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
            diffMobileTabImported.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
        } else {
            currentCols.forEach(col => col.classList.add('max-md:hidden'));
            importedCols.forEach(col => col.classList.remove('max-md:hidden'));
            
            diffMobileTabCurrent.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
            diffMobileTabImported.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
        }
    }

    // 3. Import Source Toggling (JSON vs URL)
    importJsonTabBtn.addEventListener('click', () => {
        switchSourceTab('JSON');
    });

    importUrlTabBtn.addEventListener('click', () => {
        switchSourceTab('URL');
    });

    function switchSourceTab(source) {
        if (source === 'JSON') {
            jsonInputSection.classList.remove('hidden');
            urlInputSection.classList.add('hidden');
            
            importJsonTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
            importUrlTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
        } else {
            jsonInputSection.classList.add('hidden');
            urlInputSection.classList.remove('hidden');
            
            importJsonTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
            importUrlTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
        }
    }

    // 4. Fetch Allowed Models & Expected Schema
    let modelsLoaded = false;
    function loadModels() {
        if (modelsLoaded) return;
        fetch('/api/dance-figures/guided-parse/models')
            .then(res => res.json())
            .then(models => {
                guidedModelSelect.innerHTML = '';
                models.forEach(model => {
                    const option = document.createElement('option');
                    option.value = model;
                    option.textContent = model;
                    // Default to Nemotron if present
                    if (model.includes('nemotron')) {
                        option.selected = true;
                    }
                    guidedModelSelect.appendChild(option);
                });
                modelsLoaded = true;
            })
            .catch(err => {
                console.error("Failed to load free OpenRouter models", err);
                showToast("Failed to load models list from server.", "error");
            });
    }

    let schemaLoaded = false;
    toggleSchemaBtn.addEventListener('click', (e) => {
        e.preventDefault();
        const isHidden = expectedSchemaBox.classList.contains('hidden');
        if (isHidden) {
            expectedSchemaBox.classList.remove('hidden');
            if (!schemaLoaded) {
                schemaDisplayPre.textContent = "Loading schema format...";
                fetch('/api/dance-figures/guided-parse/schema')
                    .then(res => res.text())
                    .then(schema => {
                        schemaDisplayPre.textContent = schema;
                        schemaLoaded = true;
                    })
                    .catch(err => {
                        schemaDisplayPre.textContent = "Error loading schema format.";
                        console.error(err);
                    });
            }
        } else {
            expectedSchemaBox.classList.add('hidden');
        }
    });

    // 5. CSRF Token Retrieval
    function getCsrfHeaders() {
        const tokenMeta = document.querySelector('meta[name="_csrf"]');
        const headerMeta = document.querySelector('meta[name="_csrf_header"]');
        const headers = {
            'Content-Type': 'application/json'
        };
        if (tokenMeta && headerMeta) {
            headers[headerMeta.getAttribute('content')] = tokenMeta.getAttribute('content');
        }
        return headers;
    }

    // 6. JSON Validation & Parser Trigger
    validateJsonBtn.addEventListener('click', () => {
        const rawJson = guidedJsonInput.value.trim();
        if (!rawJson) {
            showToast("Please paste JSON data first.", "warning");
            return;
        }

        try {
            JSON.parse(rawJson);
        } catch (e) {
            showToast("Malformed JSON: " + e.message, "error");
            return;
        }

        setLoading(true);
        fetch('/api/dance-figures/guided-parse/json', {
            method: 'POST',
            headers: getCsrfHeaders(),
            body: JSON.stringify({ json: rawJson })
        })
        .then(res => res.json())
        .then(res => handleParseResponse(res))
        .catch(err => {
            console.error(err);
            setLoading(false);
            const errorMsg = "Server error validating JSON: " + err.message;
            showToast(errorMsg, "error");
            if (guidedErrorContainer && guidedErrorMessage) {
                guidedErrorMessage.textContent = errorMsg;
                guidedErrorContainer.classList.remove('hidden');
                guidedErrorContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        });
    });

    parseUrlBtn.addEventListener('click', () => {
        const url = guidedUrlInput.value.trim();
        if (!url) {
            showToast("Please enter a webpage URL.", "warning");
            return;
        }

        const model = guidedModelSelect.value;
        const danceTypeId = document.getElementById('danceTypeId')?.value || null;

        // Extract optional parameters
        const maxTokensVal = guidedMaxTokens ? parseInt(guidedMaxTokens.value, 10) : null;
        const tempVal = guidedTemperature ? parseFloat(guidedTemperature.value) : null;
        const reasoningEffortVal = guidedReasoningEffort ? guidedReasoningEffort.value : 'default';

        setLoading(true);
        fetch('/api/dance-figures/guided-parse/url', {
            method: 'POST',
            headers: getCsrfHeaders(),
            body: JSON.stringify({
                url: url,
                model: model,
                danceTypeId: danceTypeId ? danceTypeId : null,
                maxTokens: isNaN(maxTokensVal) ? null : maxTokensVal,
                temperature: isNaN(tempVal) ? null : tempVal,
                reasoningEffort: reasoningEffortVal
            })
        })
        .then(res => res.json())
        .then(res => {
            if (res.success) {
                saveUrlToHistory(url);
            }
            handleParseResponse(res);
        })
        .catch(err => {
            console.error(err);
            setLoading(false);
            const errorMsg = "Server error calling AI agent to parse page: " + err.message;
            showToast(errorMsg, "error");
            if (guidedErrorContainer && guidedErrorMessage) {
                guidedErrorMessage.textContent = errorMsg;
                guidedErrorContainer.classList.remove('hidden');
                guidedErrorContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        });
    });

    function setLoading(isLoading) {
        if (isLoading) {
            importLoadingState.classList.remove('hidden');
            diffComparisonSection.classList.add('hidden');
            if (guidedErrorContainer) guidedErrorContainer.classList.add('hidden');
            if (tokenStatsContainer) tokenStatsContainer.classList.add('hidden');
            validateJsonBtn.disabled = true;
            parseUrlBtn.disabled = true;
        } else {
            importLoadingState.classList.add('hidden');
            validateJsonBtn.disabled = false;
            parseUrlBtn.disabled = false;
        }
    }

    function handleParseResponse(res) {
        setLoading(false);
        console.log("Guided parse result:", res);

        // Display usage statistics if available
        if (res.usage && tokenStatsContainer) {
            statPromptTokens.textContent = res.usage.promptTokens || 0;
            statCompletionTokens.textContent = res.usage.completionTokens || 0;
            statReasoningTokens.textContent = res.usage.reasoningTokens !== null ? res.usage.reasoningTokens : "N/A";
            statTotalTokens.textContent = res.usage.totalTokens || 0;
            tokenStatsContainer.classList.remove('hidden');
        }

        if (!res.success) {
            const errorMsg = res.errors.join(", ") || "Failed to parse data.";
            showToast(errorMsg, "error");
            if (guidedErrorContainer && guidedErrorMessage) {
                guidedErrorMessage.textContent = errorMsg;
                guidedErrorContainer.classList.remove('hidden');
                guidedErrorContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
            return;
        }

        parsedResultData = res.request;
        renderDiffView(res.request);
        showToast("Data parsed successfully! Please review the changes below.", "success");
    }

    // 8. Diff Comparison Renderer
    const fieldsDefinition = [
        { key: 'name', label: 'Figure Name', type: 'text', protect: true },
        { key: 'danceTypeId', label: 'Dance Style', type: 'select_dance_type', protect: true },
        { key: 'danceClass', label: 'Minimum Class', type: 'select_dance_class' },
        { key: 'alternativeTiming', label: 'Alt Timing', type: 'text' },
        { key: 'startingPosition', label: 'Starting Position', type: 'text' },
        { key: 'endingPosition', label: 'Ending Position', type: 'text' },
        { key: 'startingFootLeader', label: 'Start Foot (Leader)', type: 'text' },
        { key: 'endingFootLeader', label: 'End Foot (Leader)', type: 'text' },
        { key: 'startingFootFollower', label: 'Start Foot (Follower)', type: 'text' },
        { key: 'endingFootFollower', label: 'End Foot (Follower)', type: 'text' },
        { key: 'precedingFigureNames', label: 'Preceding Figures', type: 'list' },
        { key: 'followingFigureNames', label: 'Following Figures', type: 'list' },
        { key: 'notes', label: 'Notes & Details', type: 'textarea' }
    ];

    function renderDiffView(imported) {
        diffTableBody.innerHTML = '';
        
        fieldsDefinition.forEach(field => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-surface-container/20';

            // Get Current Form Value
            let currentDisplay = '';
            let currentValue = '';
            
            if (field.type === 'select_dance_type') {
                const selectEl = document.getElementById('danceTypeId');
                currentValue = selectEl?.value || '';
                currentDisplay = selectEl?.options[selectEl.selectedIndex]?.text || '';
                if (currentDisplay === '-- Select Dance Style --') currentDisplay = '';
            } else if (field.type === 'select_dance_class') {
                const selectEl = document.getElementById('danceClass');
                currentValue = selectEl?.value || '';
                currentDisplay = selectEl?.options[selectEl.selectedIndex]?.text || '';
                if (currentDisplay === 'None (Social/General)') currentDisplay = '';
            } else if (field.type === 'list') {
                const selectEl = document.getElementById(field.key);
                const selected = selectEl ? Array.from(selectEl.options).filter(opt => opt.selected).map(opt => opt.value) : [];
                currentValue = JSON.stringify(selected);
                currentDisplay = selected.join(', ');
            } else {
                const inputEl = document.getElementById(field.key);
                currentValue = inputEl?.value || '';
                currentDisplay = currentValue;
            }

            // Get Imported Value
            let importedDisplay = '';
            let importedVal = imported[field.key];
            if (field.key === 'danceTypeId' && importedVal) {
                // Find dance style name by UUID
                const selectEl = document.getElementById('danceTypeId');
                const opt = selectEl ? Array.from(selectEl.options).find(o => o.value === importedVal) : null;
                importedDisplay = opt ? opt.textContent : 'Style Not Found';
            } else if (field.key === 'danceClass' && importedVal) {
                importedDisplay = 'Class ' + importedVal;
            } else if (Array.isArray(importedVal)) {
                importedDisplay = importedVal.join(', ');
            } else {
                importedDisplay = importedVal || '';
            }

            // Decide check state
            // If field is protected (Name/Style) and we are in Edit mode, force unchecked & disabled
            const isProtected = field.protect && isEditPage;
            // Otherwise, check by default if current form value is blank and imported is not blank
            const shouldCheck = !isProtected && (!currentDisplay && importedDisplay);

            const isDifferent = currentDisplay.trim().toLowerCase() !== importedDisplay.trim().toLowerCase();

            // RENDER COLS
            // Col 1: Checkbox
            const tdCheck = document.createElement('td');
            tdCheck.className = 'p-3 text-center';
            tdCheck.innerHTML = `
                <input type="checkbox" data-field="${field.key}" ${shouldCheck ? 'checked' : ''} ${isProtected ? 'disabled' : ''} 
                       class="diff-field-checkbox rounded border-border text-primary focus:ring-primary h-4 w-4"/>
            `;

            // Col 2: Field Name
            const tdLabel = document.createElement('td');
            tdLabel.className = 'p-3 font-medium text-on-surface flex items-center justify-between gap-2 md:table-cell';
            tdLabel.innerHTML = `
                <span>${field.label}</span>
                ${isDifferent && !isProtected ? '<span class="badge badge-accent text-[9px] py-0.5 px-1.5 md:hidden">Changed</span>' : ''}
            `;

            // Col 3: Current Form Value
            const tdCurrent = document.createElement('td');
            tdCurrent.className = 'p-3 text-on-surface-variant font-mono text-[11px] whitespace-pre-line js-col-current md:table-cell';
            tdCurrent.textContent = currentDisplay || '(empty)';

            // Col 4: Imported Value (Editable Input)
            const tdImported = document.createElement('td');
            tdImported.className = 'p-3 js-col-imported max-md:hidden md:table-cell';
            
            let editableHtml = '';
            if (field.type === 'select_dance_type') {
                // Style select clone
                const selectClone = document.getElementById('danceTypeId').cloneNode(true);
                selectClone.id = `diff-input-${field.key}`;
                selectClone.removeAttribute('name');
                selectClone.removeAttribute('required');
                selectClone.className = 'form-select text-xs py-1.5 w-full';
                if (importedVal) selectClone.value = importedVal;
                if (isProtected) selectClone.disabled = true;
                tdImported.appendChild(selectClone);
            } else if (field.type === 'select_dance_class') {
                // Class select clone
                const selectClone = document.getElementById('danceClass').cloneNode(true);
                selectClone.id = `diff-input-${field.key}`;
                selectClone.removeAttribute('name');
                selectClone.className = 'form-select text-xs py-1.5 w-full';
                if (importedVal) selectClone.value = importedVal;
                tdImported.appendChild(selectClone);
            } else if (field.type === 'textarea' || field.key === 'notes') {
                const textarea = document.createElement('textarea');
                textarea.id = `diff-input-${field.key}`;
                textarea.className = 'form-textarea w-full text-xs p-2 rounded border-border';
                textarea.rows = 2;
                textarea.value = importedDisplay;
                tdImported.appendChild(textarea);
            } else {
                const input = document.createElement('input');
                input.id = `diff-input-${field.key}`;
                input.type = 'text';
                input.className = 'form-input text-xs py-1.5 w-full';
                input.value = importedDisplay;
                if (isProtected) input.readOnly = true;
                tdImported.appendChild(input);
            }

            if (isDifferent && !isProtected) {
                tr.classList.add('bg-primary-container/5');
            }

            tr.appendChild(tdCheck);
            tr.appendChild(tdLabel);
            tr.appendChild(tdCurrent);
            tr.appendChild(tdImported);
            diffTableBody.appendChild(tr);
        });

        // Show metadata counts for steps and links
        const leaderCount = imported.steps?.filter(s => s.role === 'LEADER').length || 0;
        const followerCount = imported.steps?.filter(s => s.role === 'FOLLOWER').length || 0;
        const linksCount = imported.links?.length || 0;

        const metadataCheckbox = document.getElementById('import-section-metadata');
        if (metadataCheckbox) metadataCheckbox.checked = true;

        const stepsCheckbox = document.getElementById('import-section-steps');
        if (stepsCheckbox) stepsCheckbox.checked = true;
        const stepsText = document.getElementById('import-section-steps-text');
        if (stepsText) {
            stepsText.textContent = `Steps breakdown (${leaderCount} L / ${followerCount} F)`;
        }

        const linksCheckbox = document.getElementById('import-section-links');
        if (linksCheckbox) linksCheckbox.checked = true;
        const linksText = document.getElementById('import-section-links-text');
        if (linksText) {
            linksText.textContent = `Resource links (${linksCount})`;
        }

        // Populate Steps Preview Collapsible
        const stepsPreviewContainer = document.getElementById('diff-steps-preview-container');
        const stepsPreviewTbody = document.getElementById('diff-steps-preview-tbody');
        const stepsPreviewCount = document.getElementById('diff-steps-preview-count');
        
        if (stepsPreviewContainer && stepsPreviewTbody && stepsPreviewCount) {
            stepsPreviewTbody.innerHTML = '';
            const totalSteps = imported.steps?.length || 0;
            if (totalSteps > 0) {
                stepsPreviewCount.textContent = totalSteps;
                stepsPreviewContainer.classList.remove('hidden');
                
                imported.steps.forEach(step => {
                    const tr = document.createElement('tr');
                    tr.className = 'hover:bg-surface-container/10 border-b border-border/30';
                    tr.innerHTML = `
                        <td class="py-2 pr-2 font-semibold ${step.role === 'LEADER' ? 'text-primary' : 'text-secondary'}">${step.role}</td>
                        <td class="py-2 pr-2 font-mono">${step.stepNumber}</td>
                        <td class="py-2 pr-2 font-mono">${step.timing || ''}</td>
                        <td class="py-2 pr-2">${step.foot || ''}</td>
                        <td class="py-2 pr-2 whitespace-pre-wrap">${step.action || ''}</td>
                        <td class="py-2 pr-2">${step.footwork || ''}</td>
                        <td class="py-2 pr-2">${step.alignment || ''}</td>
                        <td class="py-2 pr-2">${step.amountOfTurn || ''}</td>
                    `;
                    stepsPreviewTbody.appendChild(tr);
                });
            } else {
                stepsPreviewContainer.classList.add('hidden');
            }
        }

        // Populate Links Preview Collapsible
        const linksPreviewContainer = document.getElementById('diff-links-preview-container');
        const linksPreviewList = document.getElementById('diff-links-preview-list');
        const linksPreviewCount = document.getElementById('diff-links-preview-count');
        
        if (linksPreviewContainer && linksPreviewList && linksPreviewCount) {
            linksPreviewList.innerHTML = '';
            const totalLinks = imported.links?.length || 0;
            if (totalLinks > 0) {
                linksPreviewCount.textContent = totalLinks;
                linksPreviewContainer.classList.remove('hidden');
                
                imported.links.forEach(link => {
                    const li = document.createElement('li');
                    li.innerHTML = `<span class="font-medium">${link.title || 'Reference Link'}:</span> <a href="${link.url}" target="_blank" class="text-primary hover:underline font-mono break-all">${link.url}</a> (${link.type || 'syllabus'})`;
                    linksPreviewList.appendChild(li);
                });
            } else {
                linksPreviewContainer.classList.add('hidden');
            }
        }

        diffComparisonSection.classList.remove('hidden');
        diffComparisonSection.scrollIntoView({ behavior: 'smooth' });
    }

    // 9. Apply Imports to Form
    applyImportBtn.addEventListener('click', () => {
        if (!parsedResultData) return;

        // Verify partial section check states
        const importMetadata = document.getElementById('import-section-metadata').checked;
        const importSteps = document.getElementById('import-section-steps').checked;
        const importLinks = document.getElementById('import-section-links').checked;

        // Apply Metadata fields if checked
        if (importMetadata) {
            const checkboxes = document.querySelectorAll('.diff-field-checkbox');
            checkboxes.forEach(cb => {
                if (cb.checked && !cb.disabled) {
                    const fieldKey = cb.getAttribute('data-field');
                    const inputElement = document.getElementById(`diff-input-${fieldKey}`);
                    if (!inputElement) return;

                    const targetVal = inputElement.value;
                    const fieldDef = fieldsDefinition.find(f => f.key === fieldKey);

                    if (fieldDef.type === 'select_dance_type' || fieldDef.type === 'select_dance_class') {
                        const formSelect = document.getElementById(fieldKey);
                        if (formSelect) {
                            formSelect.value = targetVal;
                            // Trigger change event to load sequences lists if style changed
                            formSelect.dispatchEvent(new Event('change'));
                        }
                    } else if (fieldDef.type === 'list') {
                        // Multi-select list (preceding/following figures)
                        const formSelect = document.getElementById(fieldKey);
                        if (formSelect) {
                            // Split comma separated list back into selection array
                            const values = targetVal.split(',').map(s => s.trim()).filter(Boolean);
                            // Clear selections
                            Array.from(formSelect.options).forEach(opt => opt.selected = false);
                            
                            // Check if options exist, if not create them
                            values.forEach(v => {
                                let opt = Array.from(formSelect.options).find(o => o.value === v);
                                if (!opt) {
                                    opt = document.createElement('option');
                                    opt.value = v;
                                    opt.textContent = v;
                                    formSelect.appendChild(opt);
                                }
                                opt.selected = true;
                            });
                        }
                    } else {
                        // Text inputs & textareas
                        const formInput = document.getElementById(fieldKey);
                        if (formInput) formInput.value = targetVal;
                    }
                }
            });
        }

        // Apply Steps breakdown if checked
        if (importSteps && parsedResultData.steps && parsedResultData.steps.length > 0) {
            // Clear existing steps rows
            document.querySelectorAll('#leader-steps-tbody .step-row').forEach(row => row.remove());
            document.querySelectorAll('#follower-steps-tbody .step-row').forEach(row => row.remove());

            // Add new steps rows dynamically using the addStepRow from dance-figure-form.js
            parsedResultData.steps.forEach(step => {
                const role = step.role.toUpperCase(); // "LEADER" or "FOLLOWER"
                addStepRow(role);

                // Find the newly appended row (last step-row inside tbody)
                const tbody = document.getElementById(role.toLowerCase() + '-steps-tbody');
                const lastRow = tbody.lastElementChild;

                if (lastRow) {
                    // Populate row inputs
                    const timingInput = lastRow.querySelector('input[name$=".timing"]');
                    const footInput = lastRow.querySelector('input[name$=".foot"]');
                    const actionInput = lastRow.querySelector('input[name$=".action"]');
                    const footworkInput = lastRow.querySelector('input[name$=".footwork"]');
                    const alignmentInput = lastRow.querySelector('input[name$=".alignment"]');
                    const amountOfTurnInput = lastRow.querySelector('input[name$=".amountOfTurn"]');
                    const commentsTextarea = lastRow.querySelector('textarea[name$=".commentsText"]');

                    if (timingInput) timingInput.value = step.timing || '';
                    if (footInput) footInput.value = step.foot || '';
                    if (actionInput) actionInput.value = step.action || '';
                    if (footworkInput) footworkInput.value = step.footwork || '';
                    if (alignmentInput) alignmentInput.value = step.alignment || '';
                    if (amountOfTurnInput) amountOfTurnInput.value = step.amountOfTurn || '';
                    if (commentsTextarea) commentsTextarea.value = step.commentsText || '';
                }
            });
            reindexSteps();
        }

        // Apply resource links if checked
        if (importLinks && parsedResultData.links && parsedResultData.links.length > 0) {
            // Clear existing links rows
            document.querySelectorAll('#links-tbody .link-row').forEach(row => row.remove());

            // Add new link rows dynamically
            parsedResultData.links.forEach(link => {
                addLinkRow();
                const tbody = document.getElementById('links-tbody');
                const lastRow = tbody.lastElementChild;

                if (lastRow) {
                    const titleInput = lastRow.querySelector('input[name$=".title"]');
                    const urlInput = lastRow.querySelector('input[name$=".url"]');
                    const typeSelect = lastRow.querySelector('select[name$=".type"]');

                    if (titleInput) titleInput.value = link.title || 'Reference Link';
                    if (urlInput) urlInput.value = link.url || '';
                    if (typeSelect) typeSelect.value = link.type || 'syllabus';
                }
            });
            reindexLinks();
        }

        showToast("Imported data successfully applied to the form!", "success");
        
        // Discard parsed state and switch back to Manual edit view
        discardImport();
        switchMainTab('MANUAL');
    });

    discardImportBtn.addEventListener('click', () => {
        discardImport();
    });

    function discardImport() {
        parsedResultData = null;
        diffTableBody.innerHTML = '';
        diffComparisonSection.classList.add('hidden');
        guidedJsonInput.value = '';
        guidedUrlInput.value = '';
        
        if (guidedErrorContainer) guidedErrorContainer.classList.add('hidden');
        if (tokenStatsContainer) tokenStatsContainer.classList.add('hidden');

        // Reset advanced inputs
        if (guidedMaxTokens) guidedMaxTokens.value = '16384';
        if (guidedTemperature) guidedTemperature.value = '1.0';
        if (guidedReasoningEffort) guidedReasoningEffort.value = 'default';

        if (diffMobileTabCurrent && diffMobileTabImported) {
            switchDiffMobileTab('CURRENT');
        }
    }

    // 10. URL History LocalStorage Management
    function loadUrlHistory() {
        const danceTypeId = document.getElementById('danceTypeId')?.value || 'default';
        const rawHistory = localStorage.getItem('guided_url_history');
        let history = [];
        try {
            if (rawHistory) history = JSON.parse(rawHistory);
        } catch (e) {
            console.error(e);
        }

        // Filter history by current dance style if we have a valid selection
        const filtered = history.filter(item => item.danceTypeId === danceTypeId || item.danceTypeId === 'default');

        if (filtered.length > 0) {
            guidedUrlHistory.innerHTML = '<option value="">-- Select a recently used URL --</option>';
            filtered.forEach(item => {
                const opt = document.createElement('option');
                opt.value = item.url;
                opt.textContent = item.url.length > 60 ? item.url.substring(0, 60) + '...' : item.url;
                guidedUrlHistory.appendChild(opt);
            });
            urlHistoryContainer.classList.remove('hidden');
        } else {
            urlHistoryContainer.classList.add('hidden');
        }
    }

    guidedUrlHistory.addEventListener('change', (e) => {
        const val = e.target.value;
        if (val) {
            guidedUrlInput.value = val;
        }
    });

    function saveUrlToHistory(url) {
        const danceTypeId = document.getElementById('danceTypeId')?.value || 'default';
        const rawHistory = localStorage.getItem('guided_url_history');
        let history = [];
        try {
            if (rawHistory) history = JSON.parse(rawHistory);
        } catch (e) {
            console.error(e);
        }

        // Avoid duplicate URLs
        history = history.filter(item => item.url.trim().toLowerCase() !== url.trim().toLowerCase());
        
        // Push new URL to front
        history.unshift({ url, danceTypeId });

        // Keep last 15 history items
        if (history.length > 15) {
            history.pop();
        }

        localStorage.setItem('guided_url_history', JSON.stringify(history));
        loadUrlHistory();
    }

    // Helper: Detect Edit Page vs Create Page
    function checkIsEditPage() {
        const action = figureForm.getAttribute('action') || '';
        // If action contains UUID format or ends with digits/ID
        return /\/[a-f0-9-]{36}$/i.test(action);
    }

    // 11. Toast notification system (Tailwind styled)
    function showToast(message, type = 'info') {
        let container = document.getElementById('toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'toast-container';
            container.className = 'fixed bottom-5 right-5 z-[9999] flex flex-col gap-2 max-w-sm w-full';
            document.body.appendChild(container);
        }

        const toast = document.createElement('div');
        let typeClasses = 'bg-white border-border text-on-surface';
        let icon = 'info';

        if (type === 'success') {
            typeClasses = 'bg-emerald-50 border-emerald-200 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-25px';
            icon = 'check_circle';
        } else if (type === 'error') {
            typeClasses = 'bg-rose-50 border-rose-200 text-rose-900 dark:bg-rose-950 dark:text-rose-25px';
            icon = 'error';
        } else if (type === 'warning') {
            typeClasses = 'bg-amber-50 border-amber-200 text-amber-900 dark:bg-amber-950 dark:text-amber-25px';
            icon = 'warning';
        }

        toast.className = `flex items-center gap-3 p-4 rounded-xl border shadow-lg ${typeClasses} transition-all duration-300 transform translate-y-2 opacity-0`;
        
        toast.innerHTML = `
            <span class="material-symbols-outlined text-[20px] shrink-0">${icon}</span>
            <p class="text-xs font-medium">${message}</p>
            <button type="button" class="ml-auto text-outline hover:text-on-surface shrink-0 js-toast-close">
                <span class="material-symbols-outlined text-[16px]">close</span>
            </button>
        `;

        container.appendChild(toast);

        // Animate in
        setTimeout(() => {
            toast.classList.remove('translate-y-2', 'opacity-0');
        }, 10);

        // Close button handler
        toast.querySelector('.js-toast-close').addEventListener('click', () => {
            dismissToast(toast);
        });

        // Auto dismiss
        setTimeout(() => {
            dismissToast(toast);
        }, 5000);
    }

    function dismissToast(toast) {
        if (!toast.parentNode) return;
        toast.classList.add('translate-y-2', 'opacity-0');
        setTimeout(() => {
            toast.remove();
        }, 300);
    }
});
