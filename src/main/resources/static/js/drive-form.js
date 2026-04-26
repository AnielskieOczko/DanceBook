/**
 * Drive upload form logic — extracted from inline <script> in form.html
 * to comply with Content Security Policy (no 'unsafe-inline' on script-src).
 */
document.addEventListener('DOMContentLoaded', function() {

    // Show current video if driveFileId is already set
    const existingId = document.getElementById('driveFileId').value;
    if (existingId) {
      document.getElementById('currentVideo').classList.remove('hidden');
      document.getElementById('currentVideoId').textContent = existingId;
    }

    // File selection handler
    document.getElementById('videoFile').addEventListener('change', function() {
      const file = this.files[0];
      if (file) {
        document.getElementById('fileLabel').innerHTML = '🎬 ' + file.name + ' (' + (file.size / (1024 * 1024)).toFixed(1) + ' MB)';
        document.getElementById('fileLabel').classList.add('border-indigo-500', 'bg-white', 'text-gray-800', 'font-medium');
        document.getElementById('uploadBtn').disabled = false;
      }
    });

    // Upload button handler
    document.getElementById('uploadBtn').addEventListener('click', function() {
      const file = document.getElementById('videoFile').files[0];
      if (!file) return;

      const uploadBtn = document.getElementById('uploadBtn');
      const saveBtn = document.getElementById('saveBtn');
      const progressContainer = document.getElementById('progressContainer');
      const progressBar = document.getElementById('progressBar');
      const progressPercent = document.getElementById('progressPercent');
      const progressLabel = document.getElementById('progressLabel');
      const uploadStatus = document.getElementById('uploadStatus');

      // Disable buttons during upload
      uploadBtn.disabled = true;
      uploadBtn.textContent = 'Uploading...';
      saveBtn.disabled = true;
      progressContainer.classList.remove('hidden');
      uploadStatus.classList.add('hidden');

      DriveUpload.upload(file,
        // onProgress
        function(percent) {
          progressBar.style.width = percent + '%';
          progressPercent.textContent = percent + '%';
          if (percent >= 100) {
            progressLabel.textContent = 'Processing...';
          }
        },
        // onSuccess
        function(fileId) {
          document.getElementById('driveFileId').value = fileId;
          progressBar.style.width = '100%';
          progressLabel.textContent = 'Complete!';
          progressBar.classList.remove('bg-indigo-600');
          progressBar.classList.add('bg-green-500');

          uploadStatus.className = 'mt-3 text-sm rounded-lg p-3 bg-green-50 text-green-700 border border-green-200';
          uploadStatus.innerHTML = '✅ Video uploaded successfully! File ID: <code class="font-mono text-xs">' + fileId + '</code>';
          uploadStatus.classList.remove('hidden');

          document.getElementById('currentVideo').classList.remove('hidden');
          document.getElementById('currentVideoId').textContent = fileId;

          uploadBtn.textContent = 'Upload';
          saveBtn.disabled = false;
        },
        // onError
        function(error) {
          uploadStatus.className = 'mt-3 text-sm rounded-lg p-3 bg-red-50 text-red-700 border border-red-200';
          uploadStatus.textContent = '❌ Upload failed: ' + error;
          uploadStatus.classList.remove('hidden');

          uploadBtn.disabled = false;
          uploadBtn.textContent = 'Retry';
          saveBtn.disabled = false;
          progressContainer.classList.add('hidden');
        }
      );
    });

    // Remove video button handler
    document.getElementById('removeVideoBtn').addEventListener('click', function() {
      if (confirm("Are you sure you want to unlink this video?\n\n⚠️ WARNING: Once you click 'Save Material', this video file will be PERMANENTLY deleted from your Google Drive storage!")) {
        // Clear the form value immediately
        document.getElementById('driveFileId').value = '';

        // Hide the video linked indicator
        document.getElementById('currentVideo').classList.add('hidden');

        // Hide any success/error status
        document.getElementById('uploadStatus').classList.add('hidden');

        // Reset the file picker
        document.getElementById('fileLabel').innerHTML = '📎 Click to select a video file...';
        document.getElementById('fileLabel').classList.remove('border-indigo-500', 'bg-white', 'text-gray-800', 'font-medium');
        document.getElementById('videoFile').value = '';

        // Disable upload button until a new file is picked
        document.getElementById('uploadBtn').disabled = true;
        document.getElementById('uploadBtn').textContent = 'Upload';

        // Reset progress bar
        document.getElementById('progressContainer').classList.add('hidden');
        document.getElementById('progressBar').style.width = '0%';
        document.getElementById('progressBar').classList.add('bg-indigo-600');
        document.getElementById('progressBar').classList.remove('bg-green-500');
      }
    });

    // Manual Drive ID input handler
    document.getElementById('manualDriveId').addEventListener('input', function() {
      document.getElementById('driveFileId').value = this.value;
    });

});
