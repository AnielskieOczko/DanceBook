/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['../templates/**/*.html'],
  darkMode: "class",
  future: {
    hoverOnlyWhenSupported: true,
  },
  theme: {
    extend: {
      colors: {
        // Noble Harmony Palette
        "inverse-on-surface": "#f3f0ef",
        "surface-container-highest": "#e5e2e1",
        "error-container": "#ffdad6",
        "tertiary-fixed-dim": "#d3c3bc",
        "tertiary": "#140d09",
        "surface-container": "#f0edec",
        "outline": "#737877",
        "primary": "#090f0f",
        "on-primary": "#ffffff",
        "surface-variant": "#e5e2e1",
        "on-surface": "#1b1c1b",
        "tertiary-fixed": "#f0dfd7",
        "on-surface-variant": "#434847",
        "surface": "#fcf9f8",
        "on-primary-fixed-variant": "#414847",
        "on-tertiary-fixed": "#221a15",
        "secondary": "#695d46",
        "background": "#fcf9f8",
        "surface-dim": "#dcd9d9",
        "error": "#ba1a1a",
        "outline-variant": "#c3c7c6",
        "on-secondary-fixed-variant": "#504530",
        "primary-fixed-dim": "#c1c8c6",
        "surface-container-low": "#f6f3f2",
        "on-background": "#1b1c1b",
        "surface-container-lowest": "#ffffff",
        "on-secondary-fixed": "#231a09",
        "inverse-primary": "#c1c8c6",
        "secondary-container": "#efdec1",
        "surface-container-high": "#eae7e7",
        "secondary-fixed": "#f2e0c4",
        "on-tertiary-container": "#968881",
        "primary-fixed": "#dde4e2",
        "primary-container": "#1e2524",
        "on-tertiary": "#ffffff",
        "surface-bright": "#fcf9f8",
        "on-tertiary-fixed-variant": "#4f453f",
        "on-secondary-container": "#6d614a",
        "surface-tint": "#59605e",
        "tertiary-container": "#2b221d",
        "on-error-container": "#93000a",
        "on-primary-fixed": "#161d1c",
        "on-error": "#ffffff",
        "secondary-fixed-dim": "#d5c4a9",
        "inverse-surface": "#313030",
        "on-secondary": "#ffffff",
        "on-primary-container": "#dde4e2",

        // Aliases for smooth transition of existing views
        "text-primary": "#1b1c1b",
        "text-secondary": "#4b5251", // Increased contrast from #737877 for accessibility
        "border": "#c8c4c2",         // Darkened from #e5e2e1 to clearly define card boundaries
        "primary-soft": "#1e2524",
        "primary-hover": "#414847",
        "surface-warm": "#f0edec",
        "secondary-hover": "#504530",
        "accent": "#59605e",         // Darkened to ensure better visibility
        "danger": "#ba1a1a",
        "danger-soft": "#ffdad6",
        "success": "#2e5d51",        // Slightly darkened for improved contrast
        "success-soft": "#e6e2dc"
      },
      borderRadius: {
        "DEFAULT": "0.5rem",
        "sm": "0.25rem",
        "md": "0.75rem",
        "lg": "1rem",
        "xl": "1.5rem",
        "full": "9999px",
        "card": "0.5rem",
        "button": "0.5rem",
        "pill": "9999px"
      },
      spacing: {
        "base": "8px",
        "xs": "4px",
        "sm": "12px",
        "md": "24px",
        "lg": "48px",
        "xl": "80px",
        "gutter": "32px",            // Expanded gutter space to increase layout breathing room
        "margin": "40px"             // Expanded margins
      },
      fontFamily: {
        "display-lg": ["Atkinson Hyperlegible", "Inter", "sans-serif"],
        "headline-lg": ["Atkinson Hyperlegible", "Inter", "sans-serif"],
        "headline-md": ["Atkinson Hyperlegible", "Inter", "sans-serif"],
        "body-lg": ["Atkinson Hyperlegible", "Inter", "sans-serif"],
        "body-md": ["Atkinson Hyperlegible", "Inter", "sans-serif"],
        "label-sm": ["Atkinson Hyperlegible", "Inter", "sans-serif"],
        "heading": ["Atkinson Hyperlegible", "Inter", "sans-serif"],
        "body": ["Atkinson Hyperlegible", "Inter", "sans-serif"]
      },
      fontSize: {
        "display-lg": ["52px", { "lineHeight": "1.2", "letterSpacing": "-0.02em", "fontWeight": "700" }],
        "headline-lg": ["36px", { "lineHeight": "1.3", "letterSpacing": "-0.01em", "fontWeight": "600" }],
        "headline-md": ["28px", { "lineHeight": "1.4", "fontWeight": "600" }],
        "body-lg": ["20px", { "lineHeight": "1.7", "fontWeight": "500" }],
        "body-md": ["18px", { "lineHeight": "1.7", "fontWeight": "400" }],
        "label-md": ["15px", { "lineHeight": "1.5", "letterSpacing": "0.05em", "fontWeight": "600" }],
        "label-sm": ["13px", { "lineHeight": "1.5", "letterSpacing": "0.05em", "fontWeight": "600" }]
      },
      boxShadow: {
        'ambient': '0px 4px 20px rgba(45, 45, 45, 0.05)',
        'warm-sm': '0px 4px 20px rgba(45, 45, 45, 0.05)',
        'warm-md': '0px 4px 20px rgba(45, 45, 45, 0.05)',
        'warm-lg': '0px 4px 20px rgba(45, 45, 45, 0.05)',
        'warm-xl': '0px 4px 20px rgba(45, 45, 45, 0.05)'
      }
    }
  },
  plugins: []
}