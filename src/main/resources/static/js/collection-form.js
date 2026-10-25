document.addEventListener('DOMContentLoaded', function() {
    const coverFileInput = document.getElementById('coverFileInput');
    const removeImageInput = document.getElementById('removeImageInput');
    const coverChooseBtn = document.getElementById('coverChooseBtn');
    const coverChangeBtn = document.getElementById('coverChangeBtn');
    const coverRemoveBtn = document.getElementById('coverRemoveBtn');
    const coverImg = document.getElementById('coverImg');
    const coverPlaceholderIcon = document.getElementById('coverPlaceholderIcon');
    const coverTitle = document.getElementById('coverTitle');
    const coverSubtitle = document.getElementById('coverSubtitle');
    const nameFilter = document.getElementById('nameFilter');

    function show(el) {
        if (!el) return;
        el.classList.remove('hidden', 'hidden!');
        el.removeAttribute('hidden');
        el.style.removeProperty('display');
    }

    function hide(el) {
        if (!el) return;
        el.classList.add('hidden', 'hidden!');
        el.setAttribute('hidden', '');
        el.style.setProperty('display', 'none', 'important');
    }

    if (nameFilter) {
        nameFilter.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
            }
        });
    }

    if (coverFileInput) {
        coverFileInput.addEventListener('change', function(e) {
            const file = e.target.files && e.target.files[0];
            if (file) {
                const reader = new FileReader();
                reader.onload = function(evt) {
                    if (coverImg) {
                        coverImg.src = evt.target.result;
                        show(coverImg);
                    }
                    if (coverPlaceholderIcon) {
                        hide(coverPlaceholderIcon);
                    }
                    if (coverTitle) {
                        coverTitle.textContent = file.name;
                    }
                    if (coverSubtitle) {
                        const sizeMb = (file.size / (1024 * 1024)).toFixed(1);
                        coverSubtitle.textContent = sizeMb + ' MB · ' + (coverSubtitle.getAttribute('data-hint') || 'PNG, JPG or GIF');
                    }
                    hide(coverChooseBtn);
                    show(coverChangeBtn);
                    show(coverRemoveBtn);
                    if (removeImageInput) removeImageInput.value = 'false';
                };
                reader.readAsDataURL(file);
            }
        });
    }

    if (coverRemoveBtn) {
        coverRemoveBtn.addEventListener('click', function() {
            if (coverFileInput) {
                coverFileInput.value = '';
            }
            if (removeImageInput) {
                removeImageInput.value = 'true';
            }
            if (coverImg) {
                coverImg.src = '';
                hide(coverImg);
            }
            if (coverPlaceholderIcon) {
                show(coverPlaceholderIcon);
            }
            if (coverTitle) {
                coverTitle.textContent = coverTitle.getAttribute('data-default-title') || 'No cover yet';
            }
            if (coverSubtitle) {
                coverSubtitle.textContent = coverSubtitle.getAttribute('data-default-subtitle') || 'Optional · PNG, JPG or GIF, up to 10 MB';
            }
            show(coverChooseBtn);
            hide(coverChangeBtn);
            hide(coverRemoveBtn);
        });
    }
});
