(function() {
    const printBtn = document.getElementById('printBtn');
    if (printBtn) {
        printBtn.addEventListener('click', function() {
            window.print();
        });
    }

    // Close the LOD Color Guide legend when clicking anywhere outside of it
    const colorGuide = document.getElementById('lod-color-guide');
    if (colorGuide) {
        document.addEventListener('click', function(event) {
            // If the color guide is open and the click target is NOT inside the color guide
            if (colorGuide.hasAttribute('open') && !colorGuide.contains(event.target)) {
                colorGuide.removeAttribute('open');
            }
        });
    }
})();
