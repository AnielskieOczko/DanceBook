/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['../templates/**/*.html'],
  theme: {
    extend: {
      /* ──────────────────────────────────────────────
       * COLOR TOKENS — "Warm Studio" palette
       * Change these values to re-theme the entire app.
       * ────────────────────────────────────────────── */
      colors: {
        background:     '#FBF8F4',   // Warm cream base
        surface:        '#FFFFFF',   // Cards, panels
        'surface-warm': '#F5EEE6',   // Secondary surfaces, sidebar bg
        primary: {
          DEFAULT:      '#C2410C',   // Burnt Orange — CTAs, active nav
          soft:         '#FED7AA',   // Tinted backgrounds, hover fills
          hover:        '#9A3412',   // Darker for hover states
        },
        secondary: {
          DEFAULT:      '#3C7969',   // Forest Green — secondary actions
          hover:        '#2D5C50',   // Darker green hover
        },
        accent:         '#D97706',   // Warm Amber — stars, highlights
        'text-primary': '#1C1917',   // Warm black for headings/body
        'text-secondary': '#78716C', // Captions, metadata, timestamps
        border:         '#E7E0D8',   // Warm subtle dividers
        danger: {
          DEFAULT:      '#DC2626',   // Delete actions
          soft:         '#FEE2E2',   // Danger background
        },
        success: {
          DEFAULT:      '#16A34A',   // Confirmations
          soft:         '#DCFCE7',   // Success background
        },
      },

      /* ──────────────────────────────────────────────
       * TYPOGRAPHY
       * ────────────────────────────────────────────── */
      fontFamily: {
        heading: ['"Space Grotesk"', 'system-ui', 'sans-serif'],
        body:    ['"Inter"', 'system-ui', 'sans-serif'],
      },

      /* ──────────────────────────────────────────────
       * BORDER RADIUS
       * ────────────────────────────────────────────── */
      borderRadius: {
        card:   '12px',
        button: '8px',
        pill:   '9999px',
      },

      /* ──────────────────────────────────────────────
       * BOX SHADOWS — warm-toned
       * ────────────────────────────────────────────── */
      boxShadow: {
        'warm-sm':  '0 1px 3px rgba(28,25,23,0.06)',
        'warm-md':  '0 4px 12px rgba(28,25,23,0.08)',
        'warm-lg':  '0 8px 24px rgba(28,25,23,0.12)',
        'warm-xl':  '0 12px 36px rgba(28,25,23,0.16)',
      },
    },
  },
  plugins: [],
}
