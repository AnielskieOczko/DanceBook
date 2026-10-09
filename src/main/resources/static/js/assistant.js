// The assistant widget (#148). Loaded only when the assistant exists (layout.html).
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

    const wide = window.matchMedia('(min-width: 768px)');
    let firstOpen = true;

    // On desktop the open panel has its own composer, so the floating bar steps aside rather than overlap it.
    function syncBar() {
        bar.hidden = surface.open && wide.matches;
    }
    surface.addEventListener('close', syncBar);

    function openSurface() {
        if (!surface.open) {
            // Desktop: a side panel the page stays usable next to. Phone: a full-height modal sheet.
            if (wide.matches) surface.show(); else surface.showModal();
        }
        if (firstOpen) {
            firstOpen = false;
            thread.dispatchEvent(new CustomEvent('assistant-open'));
        }
        syncBar();
        composerInput.focus();
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
    });

    // What this request sent, so the reply clears the box only if nothing new was typed meanwhile.
    let sentText = '';
    document.addEventListener('htmx:beforeRequest', function (event) {
        if (event.target === composer) sentText = composerInput.value;
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
