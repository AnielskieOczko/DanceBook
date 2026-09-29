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

    function openSurface() {
        if (!surface.open) {
            // Desktop: a side panel the page stays usable next to. Phone: a full-height modal sheet.
            if (wide.matches) surface.show(); else surface.showModal();
        }
        if (firstOpen) {
            firstOpen = false;
            thread.dispatchEvent(new CustomEvent('assistant-open'));
        }
        composerInput.focus();
    }

    // Move text into the composer and send it through the composer's own htmx request.
    function ask(text) {
        const message = (text || '').trim();
        if (!message) return;
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
        const prompt = event.target.closest('[data-assistant-prompt]');
        if (prompt) {
            ask(prompt.getAttribute('data-assistant-prompt'));
        }
    });

    bar.addEventListener('submit', function (event) {
        event.preventDefault();
        const message = barInput.value;
        barInput.value = '';
        ask(message);
    });

    // The server stored the user's message: clear the box, drop the greeting, follow the thread.
    document.addEventListener('assistant-sent', function () {
        composerInput.value = '';
        const greeting = thread.querySelector('[data-assistant-empty]');
        if (greeting) greeting.remove();
        thread.scrollTop = thread.scrollHeight;
    });

    document.addEventListener('htmx:afterSwap', function (event) {
        if (thread.contains(event.target) || event.target === thread) {
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
                button.setAttribute('aria-label', 'Dictate');
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
                button.setAttribute('aria-label', 'Stop dictating');
                recognition.start();
            });
        });
    }
})();
