// The assistant widget (#148, #226). Loaded only when the assistant exists (layout.html).
// main.js has already run: renderIcon is defined. Listeners go on `document`, never on
// document.body (main.js loads in <head>, where body is still null).
(function () {
    'use strict';

    const surface = document.getElementById('assistantSurface');
    const thread = document.getElementById('assistantThread');
    const composer = document.getElementById('assistantComposer');
    const composerInput = document.getElementById('assistantComposerInput');
    const bar = document.getElementById('assistantBar');
    const barInput = document.getElementById('assistantBarInput');
    if (!surface || !thread || !composer || !composerInput || !bar || !barInput) return;

    const expandBtn = document.getElementById('assistantExpandBtn');
    const collapseBtn = document.getElementById('assistantCollapseBtn');
    const rail = document.getElementById('assistantRail');
    const railBody = document.getElementById('assistantRailBody');
    const titleEl = document.getElementById('assistantTitle');
    const defaultTitle = titleEl ? titleEl.textContent : 'Assistant';
    const widget = document.getElementById('assistantWidget');
    const pageType = widget ? (widget.getAttribute('data-page-type') || 'OTHER') : 'OTHER';

    const wide = window.matchMedia('(min-width: 768px)');
    let firstOpen = true;

    // On desktop the open panel has its own composer, so the floating bar steps aside rather than overlap it.
    function syncBar() {
        bar.hidden = surface.open && wide.matches;
    }
    surface.addEventListener('close', function () {
        if (surface.classList.contains('is-expanded')) {
            surface.classList.remove('is-expanded');
            surface.removeAttribute('data-expanded');
        }
        syncBar();
    });

    surface.addEventListener('cancel', function (event) {
        if (surface.classList.contains('is-expanded')) {
            event.preventDefault();
            collapse();
        }
    });

    function openSurface() {
        const opening = !surface.open;
        if (opening) {
            // Desktop: a side panel the page stays usable next to. Phone: a full-height modal sheet.
            if (wide.matches) surface.show(); else surface.showModal();
        }
        if (firstOpen) {
            firstOpen = false;
            thread.dispatchEvent(new CustomEvent('assistant-open'));
        }
        if (opening && surface.getAttribute('data-full-window') === 'true' && wide.matches) {
            expand();
        }
        syncBar();
        composerInput.focus();
    }

    function updateTitle() {
        if (!titleEl) return;
        if (surface.classList.contains('is-expanded')) {
            const convo = thread.querySelector('[data-conversation-title]');
            if (convo) {
                titleEl.textContent = convo.getAttribute('data-conversation-title');
                return;
            }
        }
        titleEl.textContent = defaultTitle;
    }

    function syncRailActive() {
        if (!railBody) return;
        const holder = document.getElementById('assistantConversationId');
        const activeId = holder ? holder.value : '';
        railBody.querySelectorAll('li').forEach(function (li) {
            li.classList.remove('active');
        });
        if (activeId) {
            const activeLi = document.getElementById('assistant-convo-' + activeId);
            if (activeLi) activeLi.classList.add('active');
        }
    }

    function expand() {
        if (!wide.matches) return;
        surface.classList.add('is-expanded');
        surface.setAttribute('data-expanded', 'true');

        // Direction A: if thread was showing history, switch it back to active conversation or start
        if (thread.querySelector('#assistantHistory')) {
            const holder = document.getElementById('assistantConversationId');
            if (holder && holder.value) {
                htmx.ajax('GET', '/assistant/conversations/' + encodeURIComponent(holder.value), { target: '#assistantThread', swap: 'innerHTML' });
            } else {
                htmx.ajax('GET', '/assistant/start?pageType=' + encodeURIComponent(pageType), { target: '#assistantThread', swap: 'innerHTML' });
            }
        }

        // Populate history rail
        if (railBody) {
            htmx.ajax('GET', '/assistant/conversations?pageType=' + encodeURIComponent(pageType), { target: '#assistantRailBody', swap: 'innerHTML' });
        }

        updateTitle();
        if (collapseBtn) collapseBtn.focus();
    }

    function collapse() {
        if (!surface.classList.contains('is-expanded')) return;
        surface.classList.remove('is-expanded');
        surface.removeAttribute('data-expanded');
        updateTitle();
        if (expandBtn) expandBtn.focus();
    }

    // Move text into the composer and send it through the composer's own htmx request.
    function ask(text) {
        const message = (text || '').trim();
        if (!message) return;
        if (composer.classList.contains('htmx-request')) return; // one send at a time
        openSurface();
        composerInput.value = message;
        composer.requestSubmit();
    }

    document.addEventListener('click', function (event) {
        const opener = event.target.closest('[data-assistant-open]');
        if (opener) {
            openSurface();
            return;
        }
        const prefill = event.target.closest('[data-assistant-prefill]');
        if (prefill) {
            // Wrapping up needs the user's words (what was covered), so this only starts the sentence.
            openSurface();
            composerInput.value = prefill.getAttribute('data-assistant-prefill');
            composerInput.focus();
            composerInput.setSelectionRange(composerInput.value.length, composerInput.value.length);
            return;
        }
        const prompt = event.target.closest('[data-assistant-prompt]');
        if (prompt) {
            ask(prompt.getAttribute('data-assistant-prompt'));
            return;
        }

        const exp = event.target.closest('#assistantExpandBtn');
        if (exp) {
            event.preventDefault();
            expand();
            return;
        }

        const col = event.target.closest('#assistantCollapseBtn');
        if (col) {
            event.preventDefault();
            collapse();
            return;
        }

        const renameBtn = event.target.closest('.js-rename-btn');
        if (renameBtn) {
            event.preventDefault();
            const dropdown = renameBtn.closest('.js-menu-dropdown');
            if (dropdown) dropdown.classList.add('hidden');
            const item = renameBtn.closest('li');
            if (item) {
                const view = item.querySelector('.js-convo-view');
                const form = item.querySelector('.js-convo-rename');
                if (view && form) {
                    view.classList.add('hidden');
                    form.classList.remove('hidden');
                    const input = form.querySelector('input[name="title"]');
                    if (input) {
                        input.focus();
                        input.setSelectionRange(input.value.length, input.value.length);
                    }
                }
            }
            return;
        }

        const cancelBtn = event.target.closest('.js-cancel-rename');
        if (cancelBtn) {
            event.preventDefault();
            const item = cancelBtn.closest('li');
            if (item) {
                const view = item.querySelector('.js-convo-view');
                const form = item.querySelector('.js-convo-rename');
                if (view && form) {
                    const input = form.querySelector('input[name="title"]');
                    if (input && input.hasAttribute('data-original')) {
                        input.value = input.getAttribute('data-original');
                        const counter = form.querySelector('.js-char-count');
                        if (counter) counter.textContent = input.value.length + '/80';
                    }
                    form.classList.add('hidden');
                    view.classList.remove('hidden');
                }
            }
            return;
        }
    });

    document.addEventListener('input', function (event) {
        if (event.target && event.target.matches && event.target.matches('.js-convo-rename input[name="title"]')) {
            const form = event.target.closest('form');
            if (form) {
                const counter = form.querySelector('.js-char-count');
                if (counter) counter.textContent = event.target.value.length + '/80';
            }
        }
    });

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            if (event.target && event.target.matches && event.target.matches('.js-convo-rename input[name="title"]')) {
                event.preventDefault();
                event.stopPropagation();
                const item = event.target.closest('li');
                if (item) {
                    const view = item.querySelector('.js-convo-view');
                    const form = item.querySelector('.js-convo-rename');
                    if (view && form) {
                        if (event.target.hasAttribute('data-original')) {
                            event.target.value = event.target.getAttribute('data-original');
                            const counter = form.querySelector('.js-char-count');
                            if (counter) counter.textContent = event.target.value.length + '/80';
                        }
                        form.classList.add('hidden');
                        view.classList.remove('hidden');
                    }
                }
                return;
            }

            if (surface.open && surface.classList.contains('is-expanded') && !event.defaultPrevented) {
                const confirmModal = document.getElementById('confirmModal');
                if (confirmModal && confirmModal.open) return;
                const openMenu = surface.querySelector('.js-menu-dropdown:not(.hidden)');
                if (openMenu) {
                    openMenu.classList.add('hidden');
                    event.preventDefault();
                    return;
                }
                event.preventDefault();
                collapse();
                return;
            }
        }
    });

    bar.addEventListener('submit', function (event) {
        event.preventDefault();
        if (composer.classList.contains('htmx-request')) return; // keep the text; a send is in flight
        const message = barInput.value;
        barInput.value = '';
        ask(message);
    });

    // The server says which conversation the composer is in (an empty id means a new one).
    document.addEventListener('assistant-conversation', function (event) {
        const holder = document.getElementById('assistantConversationId');
        if (holder) holder.value = (event.detail && event.detail.id) || '';
        syncRailActive();
    });

    // What this request sent, so the reply clears the box only if nothing new was typed meanwhile.
    let sentText = '';
    document.addEventListener('htmx:beforeRequest', function (event) {
        if (event.target === composer) sentText = composerInput.value;
    });

    document.addEventListener('htmx:configRequest', function (event) {
        if (event.detail.path && event.detail.path.indexOf('/delete-dialog') !== -1) {
            const elt = event.detail.elt;
            if (elt && elt.closest('#assistantRail')) {
                event.detail.parameters['target'] = '#assistantRailBody';
            }
        }
    });

    // A failed request must leave a message in the thread: main.js reports into <main>, which the
    // phone's modal sheet covers. Built with textContent, never innerHTML.
    function threadError() {
        const box = document.createElement('div');
        box.setAttribute('role', 'alert');
        box.className = 'p-4 rounded-md border bg-error/10 border-error text-error text-sm';
        box.textContent = I18n.t('js.assistant.error');
        thread.appendChild(box);
        thread.scrollTop = thread.scrollHeight;
    }
    ['htmx:responseError', 'htmx:sendError'].forEach(function (name) {
        document.addEventListener(name, function (event) {
            if (event.target && event.target.closest && event.target.closest('#assistantComposer')) threadError();
        });
    });

    // The server stored the user's message: clear the box, drop the greeting, follow the thread.
    document.addEventListener('assistant-sent', function () {
        if (composerInput.value === sentText) composerInput.value = '';
        const greeting = thread.querySelector('[data-assistant-empty]');
        if (greeting) greeting.remove();
        thread.scrollTop = thread.scrollHeight;
    });

    document.addEventListener('htmx:afterSwap', function (event) {
        if (thread.contains(event.target) || event.target === thread) {
            // The server renders which conversation the composer is in (empty = none) as a marker in
            // what it sent. Read from the swapped content: an out-of-band input or a response header
            // is lost when htmx replaces the element that made the request.
            const markers = thread.querySelectorAll('[data-conversation-id]');
            const holder = document.getElementById('assistantConversationId');
            if (markers.length && holder) holder.value = markers[markers.length - 1].getAttribute('data-conversation-id');
            if (thread.querySelector('[data-reset-conversation]') && holder) holder.value = '';
            thread.scrollTop = thread.scrollHeight;
            updateTitle();
            syncRailActive();
        } else if (railBody && (railBody.contains(event.target) || event.target === railBody)) {
            syncRailActive();
            // If the deleted conversation was the active one
            if (event.target.querySelector && event.target.querySelector('[data-reset-conversation]')) {
                const holder = document.getElementById('assistantConversationId');
                if (holder) holder.value = '';
                htmx.ajax('GET', '/assistant/start?pageType=' + encodeURIComponent(pageType), { target: '#assistantThread', swap: 'innerHTML' });
            }
            // Update title if the active conversation was renamed
            const holder = document.getElementById('assistantConversationId');
            if (holder && holder.value && surface.classList.contains('is-expanded')) {
                const convoItem = document.getElementById('assistant-convo-' + holder.value);
                if (convoItem) {
                    const titleSpan = convoItem.querySelector('.line-clamp-2');
                    if (titleSpan && titleEl) titleEl.textContent = titleSpan.textContent.trim();
                }
            }
        }
    });

    // `/` focuses the bar on desktop, except while typing somewhere else.
    document.addEventListener('keydown', function (event) {
        if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
        const target = event.target;
        if (target && (target.isContentEditable || target.closest('input, textarea, select, trix-editor, [contenteditable="true"]'))) return;
        if (!wide.matches) return;
        event.preventDefault();
        if (surface.open) { composerInput.focus(); return; }
        barInput.focus();
    });

    // Dictation: the browser's Web Speech API fills the input. Nothing but the text is sent anywhere.
    const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (Recognition) {
        document.querySelectorAll('[data-assistant-mic]').forEach(function (button) {
            button.hidden = false;
            const input = button.closest('form').querySelector('input[name="text"]');
            let recognition = null;

            function setIdle() {
                button.innerHTML = renderIcon('mic', { size: 'md' });
                button.setAttribute('aria-label', I18n.t('assistant.dictate'));
                recognition = null;
            }

            button.addEventListener('click', function () {
                if (recognition) {
                    recognition.stop();
                    return;
                }
                recognition = new Recognition();
                recognition.lang = document.documentElement.lang || 'en-US';
                recognition.interimResults = false;
                recognition.onresult = function (result) {
                    const spoken = result.results[0][0].transcript;
                    input.value = (input.value ? input.value + ' ' : '') + spoken;
                    input.focus();
                };
                recognition.onend = setIdle;
                recognition.onerror = setIdle;
                button.innerHTML = renderIcon('stop_circle', { size: 'md', cls: 'text-error' });
                button.setAttribute('aria-label', I18n.t('assistant.stop_dictating'));
                recognition.start();
            });
        });
    }
})();
