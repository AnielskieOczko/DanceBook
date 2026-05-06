document.addEventListener('htmx:configRequest', function(event) {
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (tokenMeta && headerMeta) {
        event.detail.headers[headerMeta.content] = tokenMeta.content;
    }
});

/**
 * Generic confirm-before-submit handler.
 * Usage: <form data-confirm="Are you sure?"> or <button data-confirm="Delete?">
 * Replaces inline onclick="return confirm(...)" which CSP blocks.
 */
document.addEventListener('submit', function(event) {
    const form = event.target;
    const message = form.getAttribute('data-confirm');
    if (message && !confirm(message)) {
        event.preventDefault();
    }
});

/**
 * Expandable card handler.
 * Uses event delegation to support elements loaded dynamically via HTMX.
 */
document.addEventListener('click', function(event) {
    const btn = event.target.closest('.js-expand-btn');
    if (!btn) return;
    
    event.preventDefault();
    event.stopPropagation();
    
    const article = btn.closest('article');
    if (!article) return;
    
    const content = article.querySelector('.expandable-content');
    if (!content) return;
    
    const icon = btn.querySelector('.icon-expand');
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
});
