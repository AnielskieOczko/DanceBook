document.addEventListener('DOMContentLoaded', () => {
    // Toggle Mobile Timeline collapsible details
    document.addEventListener('click', (e) => {
        const toggleBtn = e.target.closest('.js-timeline-toggle');
        if (toggleBtn) {
            const timelineItem = toggleBtn.closest('.step-timeline-item');
            if (timelineItem) {
                const details = timelineItem.querySelector('.js-timeline-details');
                const arrow = toggleBtn.querySelector('.js-timeline-arrow');
                if (details && arrow) {
                    const isHidden = details.classList.contains('hidden');
                    if (isHidden) {
                        details.classList.remove('hidden');
                        arrow.classList.add('rotate-180');
                    } else {
                        details.classList.add('hidden');
                        arrow.classList.remove('rotate-180');
                    }
                }
            }
        }
    });

    // Switch variation tabs
    document.addEventListener('click', (e) => {
        const tabBtn = e.target.closest('.js-var-tab');
        if (tabBtn) {
            const varId = tabBtn.getAttribute('data-id');
            switchVariation(varId);
        }
    });

    // Switch role tabs
    document.addEventListener('click', (e) => {
        const roleBtn = e.target.closest('.js-role-tab');
        if (roleBtn) {
            const role = roleBtn.getAttribute('data-role');
            const varId = roleBtn.getAttribute('data-var-id');
            switchRoleTab(role, varId);
        }
    });
});

function switchVariation(varId) {
    // Hide all variation panels
    document.querySelectorAll('.variation-panel').forEach(function(panel) {
        panel.classList.add('hidden');
    });
    // Show active panel
    const activePanel = document.getElementById('var-panel-' + varId);
    if (activePanel) {
        activePanel.classList.remove('hidden');
    }

    // Update tab styles
    document.querySelectorAll('[id^="var-tab-"]').forEach(function(tab) {
        tab.className = 'js-var-tab px-4 py-2 bg-surface-container hover:bg-surface-container-high text-on-surface font-semibold rounded-lg transition-all duration-150 text-sm';
    });
    const activeTab = document.getElementById('var-tab-' + varId);
    if (activeTab) {
        activeTab.className = 'js-var-tab px-4 py-2 bg-primary text-white font-bold rounded-lg shadow-sm transition-all duration-150 text-sm';
    }
}

function switchRoleTab(role, varId) {
    const isLeader = role === 'LEADER';
    
    const leaderBtn = document.getElementById('leader-tab-btn-' + varId);
    const followerBtn = document.getElementById('follower-tab-btn-' + varId);
    const leaderSec = document.getElementById('leader-steps-section-' + varId);
    const followerSec = document.getElementById('follower-steps-section-' + varId);

    if (!leaderBtn || !followerBtn || !leaderSec || !followerSec) return;

    // Toggle active styling classes on buttons
    leaderBtn.className = isLeader 
        ? 'js-role-tab flex items-center gap-2 px-5 py-2 rounded-md font-bold transition-all bg-primary-container text-on-primary-container shadow-sm'
        : 'js-role-tab flex items-center gap-2 px-5 py-2 rounded-md font-semibold transition-all text-text-secondary hover:text-on-surface';
    followerBtn.className = !isLeader 
        ? 'js-role-tab flex items-center gap-2 px-5 py-2 rounded-md font-bold transition-all bg-primary-container text-on-primary-container shadow-sm'
        : 'js-role-tab flex items-center gap-2 px-5 py-2 rounded-md font-semibold transition-all text-text-secondary hover:text-on-surface';

    // Toggle visibility of step tables
    if (isLeader) {
        leaderSec.classList.remove('hidden');
        followerSec.classList.add('hidden');
    } else {
        leaderSec.classList.add('hidden');
        followerSec.classList.remove('hidden');
    }
}
