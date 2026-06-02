document.addEventListener('DOMContentLoaded', () => {
    const leaderBtn = document.getElementById('leader-tab-btn');
    const followerBtn = document.getElementById('follower-tab-btn');

    if (leaderBtn && followerBtn) {
        leaderBtn.addEventListener('click', () => switchRoleTab('LEADER'));
        followerBtn.addEventListener('click', () => switchRoleTab('FOLLOWER'));
    }
});

function switchRoleTab(role) {
    const isLeader = role === 'LEADER';
    
    const leaderBtn = document.getElementById('leader-tab-btn');
    const followerBtn = document.getElementById('follower-tab-btn');
    const leaderSec = document.getElementById('leader-steps-section');
    const followerSec = document.getElementById('follower-steps-section');

    if (!leaderBtn || !followerBtn || !leaderSec || !followerSec) return;

    // Toggle active styling classes on buttons
    leaderBtn.className = isLeader 
        ? 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-primary-container text-primary-container'
        : 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-transparent text-text-secondary hover:text-on-surface';
    followerBtn.className = !isLeader 
        ? 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-primary-container text-primary-container'
        : 'px-4 py-1.5 rounded-md font-medium transition-all border-b-2 border-transparent text-text-secondary hover:text-on-surface';

    // Toggle visibility of step tables
    if (isLeader) {
        leaderSec.classList.remove('hidden');
        followerSec.classList.add('hidden');
    } else {
        leaderSec.classList.add('hidden');
        followerSec.classList.remove('hidden');
    }
}
