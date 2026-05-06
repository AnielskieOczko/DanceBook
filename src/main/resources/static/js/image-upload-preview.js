document.addEventListener('DOMContentLoaded', function() {
    const imageUpload = document.getElementById('imageUpload');
    if (imageUpload) {
        imageUpload.addEventListener('change', function(event) {
            const file = event.target.files[0];
            if (file) {
                const reader = new FileReader();
                reader.onload = function(e) {
                    document.getElementById('imagePreview').src = e.target.result;
                    document.getElementById('previewFilename').textContent = file.name + ' (Click to change)';
                    document.getElementById('uploadContent').classList.add('hidden');
                    document.getElementById('previewContainer').classList.remove('hidden');
                    document.getElementById('uploadBox').classList.remove('border-dashed', 'border-outline-variant');
                    document.getElementById('uploadBox').classList.add('border-solid', 'border-primary');
                }
                reader.readAsDataURL(file);
            }
        });
    }
});
