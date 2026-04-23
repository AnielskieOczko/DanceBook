document.addEventListener('htmx:configRequest', function(event) {
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (tokenMeta && headerMeta) {
        event.detail.headers[headerMeta.content] = tokenMeta.content;
    }
});

/**
 * Emoji and Emoticon Logic
 */
const emoticonMap = {
    ':)': '😊',
    ':D': '😃',
    ';)': '😉',
    ':(': '☹️',
    '<3': '❤️'
};

// Global event delegation for input and clicks
document.addEventListener('input', function(e) {
    if (e.target && e.target.id === 'comment-textarea') {
        const textarea = e.target;
        let text = textarea.value;
        let changed = false;

        for (const [key, value] of Object.entries(emoticonMap)) {
            if (text.includes(key)) {
                const start = textarea.selectionStart;
                const end = textarea.selectionEnd;
                
                text = text.replace(key, value);
                textarea.value = text;
                
                const diff = key.length - Array.from(value).length;
                textarea.setSelectionRange(start - diff, end - diff);
                changed = true;
            }
        }
    }
});

document.addEventListener('click', function(e) {
    // Toggle Emoji Grid
    if (e.target && (e.target.id === 'emoji-toggle' || e.target.closest('#emoji-toggle'))) {
        const grid = document.getElementById('emoji-grid');
        if (grid) {
            grid.classList.toggle('hidden');
        }
        return;
    }

    // Insert Emoji from Grid
    const emojiBtn = e.target.closest('.emoji-btn');
    if (emojiBtn) {
        const emoji = emojiBtn.getAttribute('data-emoji');
        const textarea = document.getElementById('comment-textarea');
        if (textarea && emoji) {
            const start = textarea.selectionStart;
            const end = textarea.selectionEnd;
            const text = textarea.value;

            textarea.value = text.substring(0, start) + emoji + text.substring(end);
            const newPos = start + Array.from(emoji).length;
            textarea.setSelectionRange(newPos, newPos);
            textarea.focus();
            
            document.getElementById('emoji-grid')?.classList.add('hidden');
        }
    } else {
        // Hide grid when clicking outside
        const grid = document.getElementById('emoji-grid');
        if (grid && !grid.classList.contains('hidden') && !e.target.closest('#emoji-toggle')) {
            grid.classList.add('hidden');
        }
    }
});
