document.addEventListener('DOMContentLoaded', () => {
    const leaderBtn = document.getElementById('leader-tab-btn');
    const followerBtn = document.getElementById('follower-tab-btn');

    if (leaderBtn && followerBtn) {
        leaderBtn.addEventListener('click', () => switchRoleTab('LEADER'));
        followerBtn.addEventListener('click', () => switchRoleTab('FOLLOWER'));
    }

    // Toggle active Step Set Group
    const stepSetSelect = document.getElementById('step-set-select');
    if (stepSetSelect) {
        stepSetSelect.addEventListener('change', (e) => {
            const selectedId = e.target.value;
            document.querySelectorAll('.js-step-set-group').forEach(group => {
                if (group.getAttribute('data-set-id') === selectedId) {
                    group.classList.remove('hidden');
                } else {
                    group.classList.add('hidden');
                }
            });
        });
    }

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
});

function switchRoleTab(role) {
    const isLeader = role === 'LEADER';
    
    const leaderBtn = document.getElementById('leader-tab-btn');
    const followerBtn = document.getElementById('follower-tab-btn');

    if (!leaderBtn || !followerBtn) return;

    // Toggle active styling classes on buttons
    leaderBtn.className = isLeader 
        ? 'flex items-center gap-2 px-5 py-2 rounded-md font-bold transition-all bg-primary-container text-on-primary-container shadow-sm'
        : 'flex items-center gap-2 px-5 py-2 rounded-md font-semibold transition-all text-text-secondary hover:text-on-surface';
    followerBtn.className = !isLeader 
        ? 'flex items-center gap-2 px-5 py-2 rounded-md font-bold transition-all bg-primary-container text-on-primary-container shadow-sm'
        : 'flex items-center gap-2 px-5 py-2 rounded-md font-semibold transition-all text-text-secondary hover:text-on-surface';

    // Toggle visibility of step tables for all groups
    document.querySelectorAll('.leader-steps-section').forEach(sec => {
        if (isLeader) {
            sec.classList.remove('hidden');
        } else {
            sec.classList.add('hidden');
        }
    });
    document.querySelectorAll('.follower-steps-section').forEach(sec => {
        if (isLeader) {
            sec.classList.add('hidden');
        } else {
            sec.classList.remove('hidden');
        }
    });
}
