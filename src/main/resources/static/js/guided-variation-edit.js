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
    const guidedProviderSelect = document.getElementById('guided-provider-select');
    const reasoningEffortContainer = document.getElementById('reasoning-effort-container');
    const thinkingBudgetContainer = document.getElementById('thinking-budget-container');
    const guidedThinkingBudget = document.getElementById('guided-thinking-budget');
    const urlHistoryContainer = document.getElementById('url-history-container');
    const guidedUrlHistory = document.getElementById('guided-url-history');
    const parseUrlBtn = document.getElementById('parse-url-btn');

    // Advanced parameters
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
        const colsCurrent = document.querySelectorAll('.js-col-current');
        const colsImported = document.querySelectorAll('.js-col-imported');
        
        if (tab === 'CURRENT') {
            colsCurrent.forEach(el => el.classList.remove('max-md:hidden'));
            colsImported.forEach(el => el.classList.add('max-md:hidden'));
            diffMobileTabCurrent.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
            diffMobileTabImported.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
        } else {
            colsCurrent.forEach(el => el.classList.add('max-md:hidden'));
            colsImported.forEach(el => el.classList.remove('max-md:hidden'));
            diffMobileTabCurrent.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
            diffMobileTabImported.className = 'flex-1 text-center py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
        }
    }

    // 3. Guided Input Type Selector (Paste JSON vs Parse URL)
    importJsonTabBtn.addEventListener('click', () => {
        jsonInputSection.classList.remove('hidden');
        urlInputSection.classList.add('hidden');
        importJsonTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
        importUrlTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
    });

    importUrlTabBtn.addEventListener('click', () => {
        jsonInputSection.classList.add('hidden');
        urlInputSection.classList.remove('hidden');
        importJsonTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all text-on-surface-variant hover:text-on-surface';
        importUrlTabBtn.className = 'px-3 py-1.5 rounded-md font-medium transition-all bg-white shadow-sm text-primary font-semibold';
    });

    // 4. Expected JSON Schema helper
    toggleSchemaBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        const isHidden = expectedSchemaBox.classList.contains('hidden');
        if (isHidden) {
            expectedSchemaBox.classList.remove('hidden');
            if (!schemaDisplayPre.textContent) {
                try {
                    const response = await fetch('/api/dance-figures/guided-parse/schema');
                    const schema = await response.text();
                    schemaDisplayPre.textContent = schema;
                } catch (e) {
                    schemaDisplayPre.textContent = "Error loading schema structure.";
                }
            }
        } else {
            expectedSchemaBox.classList.add('hidden');
        }
    });

    // 5. Providers and Models Loading
    let providersModelsMap = {};

    async function loadModels() {
        if (Object.keys(providersModelsMap).length > 0) return; // Already loaded

        try {
            const res = await fetch('/api/dance-figures/guided-parse/models');
            if (res.ok) {
                providersModelsMap = await res.json();
                populateProviders();
            }
        } catch (e) {
            console.error("Failed to load OpenRouter / Google AI models", e);
        }
    }

    function populateProviders() {
        guidedProviderSelect.innerHTML = '';
        Object.keys(providersModelsMap).forEach(provider => {
            const opt = document.createElement('option');
            opt.value = provider;
            
            let label = provider;
            if (provider === 'openrouter') label = 'OpenRouter (Advanced Clouds)';
            else if (provider === 'google-ai') label = 'Google AI (Gemini Direct)';
            else if (provider === 'ollama') label = 'Ollama (Local AI)';
            
            opt.textContent = label;
            guidedProviderSelect.appendChild(opt);
        });

        // Set Default Selection
        if (providersModelsMap['openrouter']) {
            guidedProviderSelect.value = 'openrouter';
        } else if (Object.keys(providersModelsMap).length > 0) {
            guidedProviderSelect.value = Object.keys(providersModelsMap)[0];
        }

        populateModelsForSelectedProvider();
    }

    function populateModelsForSelectedProvider() {
        const provider = guidedProviderSelect.value;
        const models = providersModelsMap[provider] || [];
        
        guidedModelSelect.innerHTML = '';
        models.forEach(model => {
            const opt = document.createElement('option');
            opt.value = model;
            opt.textContent = model;
            guidedModelSelect.appendChild(opt);
        });

        // Toggle advanced cloud inputs
        if (provider === 'openrouter') {
            reasoningEffortContainer.classList.remove('hidden');
            thinkingBudgetContainer.classList.add('hidden');
        } else if (provider === 'google-ai') {
            reasoningEffortContainer.classList.add('hidden');
            thinkingBudgetContainer.classList.remove('hidden');
        } else {
            reasoningEffortContainer.classList.add('hidden');
            thinkingBudgetContainer.classList.add('hidden');
        }
    }

    guidedProviderSelect.addEventListener('change', populateModelsForSelectedProvider);

    // 6. Paste JSON validation
    validateJsonBtn.addEventListener('click', async () => {
        const json = guidedJsonInput.value.trim();
        if (!json) {
            showToast("Please paste some JSON data first.", "warning");
            return;
        }

        setLoading(true);
        hideError();

        try {
            const res = await fetch('/api/dance-figures/guided-parse/variation/json', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ json: json })
            });
            const data = await res.json();
            
            if (data.success && data.request) {
                handleSuccessfulParse(data);
            } else {
                showError(data.errors.join('\n') || "Parsed content is empty.");
            }
        } catch (e) {
            showError("Network request failed: " + e.message);
        } finally {
            setLoading(false);
        }
    });

    // 7. Parse URL analyzer
    parseUrlBtn.addEventListener('click', async () => {
        const url = guidedUrlInput.value.trim();
        if (!url) {
            showToast("Please enter a dance syllabus URL.", "warning");
            return;
        }

        setLoading(true);
        hideError();

        const provider = guidedProviderSelect.value;
        const model = guidedModelSelect.value;
        const maxTokens = parseInt(guidedMaxTokens.value) || 16384;
        const temperature = parseFloat(guidedTemperature.value) || 1.0;

        const settings = {};
        if (provider === 'openrouter') {
            const effort = guidedReasoningEffort.value;
            if (effort !== 'default') {
                settings['reasoning_effort'] = effort;
            }
        } else if (provider === 'google-ai') {
            const budget = parseInt(guidedThinkingBudget.value);
            if (!isNaN(budget)) {
                settings['thinking_budget'] = budget;
            }
        }

        try {
            const res = await fetch('/api/dance-figures/guided-parse/variation/url', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    url: url,
                    provider: provider,
                    model: model,
                    maxTokens: maxTokens,
                    temperature: temperature,
                    providerSettings: settings
                })
            });
            const data = await res.json();
            
            if (data.success && data.request) {
                handleSuccessfulParse(data);
                saveUrlToHistory(url);
            } else {
                showError(data.errors.join('\n') || "AI model returned empty response.");
            }
        } catch (e) {
            showError("AI parsing failed: " + e.message);
        } finally {
            setLoading(false);
        }
    });

    function handleSuccessfulParse(res) {
        parsedResultData = res.request;
        
        // Show usage stats
        if (res.usage) {
            statPromptTokens.textContent = res.usage.promptTokens;
            statCompletionTokens.textContent = res.usage.completionTokens;
            statReasoningTokens.textContent = res.usage.reasoningTokens || '0';
            statTotalTokens.textContent = res.usage.totalTokens;
            tokenStatsContainer.classList.remove('hidden');
        } else {
            tokenStatsContainer.classList.add('hidden');
        }

        renderDiffView(res.request);
        showToast("Data parsed successfully! Please review the changes below.", "success");
    }

    // 8. Diff Comparison Renderer
    const fieldsDefinition = [
        { key: 'name', label: 'Variation Name', type: 'text' },
        { key: 'timing', label: 'Rhythm Timing', type: 'text' },
        { key: 'startingPosition', label: 'Starting Position', type: 'text' },
        { key: 'endingPosition', label: 'Ending Position', type: 'text' },
        { key: 'startingFootLeader', label: 'Start Foot (Leader)', type: 'text' },
        { key: 'endingFootLeader', label: 'End Foot (Leader)', type: 'text' },
        { key: 'startingFootFollower', label: 'Start Foot (Follower)', type: 'text' },
        { key: 'endingFootFollower', label: 'End Foot (Follower)', type: 'text' }
    ];

    function renderDiffView(imported) {
        diffTableBody.innerHTML = '';
        
        fieldsDefinition.forEach(field => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-surface-container/20';

            // Get Current Form Value
            const inputEl = document.getElementById(field.key);
            let currentValue = inputEl?.value || '';
            let currentDisplay = currentValue;

            // Get Imported Value
            let importedDisplay = '';
            let importedVal = imported[field.key];
            if (importedVal !== undefined && importedVal !== null) {
                importedDisplay = importedVal;
            }

            const hasDiff = currentValue !== importedDisplay;

            tr.innerHTML = `
                <td class="p-3 text-center">
                    <input type="checkbox" checked data-field="${field.key}" class="diff-field-checkbox rounded border-border text-primary focus:ring-primary h-4 w-4"/>
                </td>
                <td class="p-3 font-semibold text-on-surface">${field.label}</td>
                <td class="p-3 text-on-surface-variant font-medium js-col-current">${currentDisplay || '<span class="italic text-outline-variant">empty</span>'}</td>
                <td class="p-3 font-medium js-col-imported max-md:hidden ${hasDiff ? 'bg-primary-container/20 text-primary font-bold' : 'text-on-surface-variant'}">
                    <input type="text" id="diff-input-${field.key}" value="${importedDisplay}" class="form-input text-xs py-1 px-2 border-0 bg-transparent focus:ring-1 focus:ring-primary w-full" />
                </td>
            `;
            diffTableBody.appendChild(tr);
        });

        // Render Steps preview
        const stepsPreviewContainer = document.getElementById('diff-steps-preview-container');
        const stepsPreviewCount = document.getElementById('diff-steps-preview-count');
        const stepsPreviewTbody = document.getElementById('diff-steps-preview-tbody');

        if (stepsPreviewContainer && stepsPreviewCount && stepsPreviewTbody) {
            if (imported.steps && imported.steps.length > 0) {
                stepsPreviewCount.textContent = imported.steps.length;
                stepsPreviewTbody.innerHTML = '';
                
                imported.steps.forEach(step => {
                    const tr = document.createElement('tr');
                    tr.className = 'hover:bg-surface-container/20 border-b border-border/30';
                    tr.innerHTML = `
                        <td class="py-2 pr-2 font-bold uppercase text-outline">${step.role}</td>
                        <td class="py-2 pr-2 text-center font-bold">${step.stepNumber}</td>
                        <td class="py-2 pr-2 font-bold text-primary">${step.timing || '-'}</td>
                        <td class="py-2 pr-2">${step.foot || '-'}</td>
                        <td class="py-2 pr-2 font-medium">${step.action || ''}</td>
                        <td class="py-2 pr-2 font-mono">${step.footwork || '-'}</td>
                        <td class="py-2 pr-2">${step.alignment || '-'}</td>
                        <td class="py-2 pr-2">${step.amountOfTurn || '-'}</td>
                    `;
                    stepsPreviewTbody.appendChild(tr);
                });
                stepsPreviewContainer.classList.remove('hidden');
            } else {
                stepsPreviewContainer.classList.add('hidden');
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

        // Apply Metadata fields if checked
        if (importMetadata) {
            const checkboxes = document.querySelectorAll('.diff-field-checkbox');
            checkboxes.forEach(cb => {
                if (cb.checked && !cb.disabled) {
                    const fieldKey = cb.getAttribute('data-field');
                    const inputElement = document.getElementById(`diff-input-${fieldKey}`);
                    if (!inputElement) return;

                    const targetVal = inputElement.value;
                    const formInput = document.getElementById(fieldKey);
                    if (formInput) formInput.value = targetVal;
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

        showToast("AI parsed fields successfully applied to the manual form! Review and hit 'Save Variation' when ready.", "success");
        switchMainTab('MANUAL');
        diffComparisonSection.classList.add('hidden');
    });

    // 10. Discard button
    discardImportBtn.addEventListener('click', () => {
        parsedResultData = null;
        diffComparisonSection.classList.add('hidden');
        showToast("Imported data discarded.", "info");
    });

    // Helpers
    function setLoading(isLoading) {
        if (isLoading) {
            importLoadingState.classList.remove('hidden');
            validateJsonBtn.disabled = true;
            parseUrlBtn.disabled = true;
            diffComparisonSection.classList.add('hidden');
        } else {
            importLoadingState.classList.add('hidden');
            validateJsonBtn.disabled = false;
            parseUrlBtn.disabled = false;
        }
    }

    function showError(msg) {
        guidedErrorMessage.textContent = msg;
        guidedErrorContainer.classList.remove('hidden');
        guidedErrorContainer.scrollIntoView({ behavior: 'smooth' });
    }

    function hideError() {
        guidedErrorContainer.classList.add('hidden');
    }

    function checkIsEditPage() {
        const path = window.location.pathname;
        return path.endsWith('/edit') || path.includes('/variations/') && !path.endsWith('/new');
    }

    // Toast notifications helper
    function showToast(message, type = 'info') {
        const container = document.getElementById('toast-container') || createToastContainer();
        const toast = document.createElement('div');
        
        let bgClass = 'bg-primary-container text-on-primary-container border-primary';
        let iconName = 'info';
        
        if (type === 'success') {
            bgClass = 'bg-emerald-50 text-emerald-950 border-emerald-500';
            iconName = 'check_circle';
        } else if (type === 'warning') {
            bgClass = 'bg-amber-50 text-amber-950 border-amber-500';
            iconName = 'warning';
        } else if (type === 'danger') {
            bgClass = 'bg-rose-50 text-rose-950 border-rose-500';
            iconName = 'error';
        }
        
        toast.className = `flex items-center gap-3 p-4 rounded-xl border shadow-lg max-w-sm transition-all duration-300 transform translate-y-2 opacity-0 ${bgClass}`;
        toast.innerHTML = `
            <span class="material-symbols-outlined shrink-0 text-[20px]">${iconName}</span>
            <span class="text-xs font-semibold leading-relaxed">${message}</span>
        `;
        
        container.appendChild(toast);
        
        // Trigger animation
        setTimeout(() => {
            toast.classList.remove('translate-y-2', 'opacity-0');
        }, 10);
        
        // Auto remove
        setTimeout(() => {
            toast.classList.add('translate-y-2', 'opacity-0');
            setTimeout(() => toast.remove(), 300);
        }, 4000);
    }

    function createToastContainer() {
        const div = document.createElement('div');
        div.id = 'toast-container';
        div.className = 'fixed bottom-5 right-5 z-50 flex flex-col gap-2.5';
        document.body.appendChild(div);
        return div;
    }

    // Local Storage Syllabus URLs History
    const STORAGE_KEY = 'dancebook_guided_variation_urls';

    function saveUrlToHistory(url) {
        try {
            let history = JSON.parse(localStorage.getItem(STORAGE_KEY)) || [];
            history = history.filter(u => u !== url);
            history.unshift(url);
            history = history.slice(0, 10); // keep last 10
            localStorage.setItem(STORAGE_KEY, JSON.stringify(history));
            loadUrlHistory();
        } catch (e) {
            console.error("Local storage access failed", e);
        }
    }

    function loadUrlHistory() {
        try {
            const history = JSON.parse(localStorage.getItem(STORAGE_KEY)) || [];
            if (history.length > 0) {
                guidedUrlHistory.innerHTML = '<option value="">-- Select a recently used URL --</option>';
                history.forEach(url => {
                    const opt = document.createElement('option');
                    opt.value = url;
                    opt.textContent = url.length > 60 ? url.substring(0, 57) + '...' : url;
                    guidedUrlHistory.appendChild(opt);
                });
                urlHistoryContainer.classList.remove('hidden');
            } else {
                urlHistoryContainer.classList.add('hidden');
            }
        } catch (e) {
            urlHistoryContainer.classList.add('hidden');
        }
    }

    guidedUrlHistory.addEventListener('change', () => {
        const val = guidedUrlHistory.value;
        if (val) {
            guidedUrlInput.value = val;
        }
    });
});
