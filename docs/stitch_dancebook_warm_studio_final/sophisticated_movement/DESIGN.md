---
name: Sophisticated Movement
colors:
  surface: '#fcf9f8'
  surface-dim: '#dcd9d9'
  surface-bright: '#fcf9f8'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f6f3f2'
  surface-container: '#f0eded'
  surface-container-high: '#eae7e7'
  surface-container-highest: '#e4e2e1'
  on-surface: '#1b1c1c'
  on-surface-variant: '#4c463c'
  inverse-surface: '#303030'
  inverse-on-surface: '#f3f0f0'
  outline: '#7d766b'
  outline-variant: '#cfc5b9'
  surface-tint: '#6b5c41'
  primary: '#6b5c41'
  on-primary: '#ffffff'
  primary-container: '#d9c5a3'
  on-primary-container: '#605136'
  inverse-primary: '#d8c4a2'
  secondary: '#605e5a'
  on-secondary: '#ffffff'
  secondary-container: '#e6e2dc'
  on-secondary-container: '#666460'
  tertiary: '#5a5d71'
  on-tertiary: '#ffffff'
  tertiary-container: '#c4c6dd'
  on-tertiary-container: '#4f5265'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#f5e0bd'
  primary-fixed-dim: '#d8c4a2'
  on-primary-fixed: '#241a05'
  on-primary-fixed-variant: '#52452b'
  secondary-fixed: '#e6e2dc'
  secondary-fixed-dim: '#c9c6c0'
  on-secondary-fixed: '#1c1c18'
  on-secondary-fixed-variant: '#484743'
  tertiary-fixed: '#dfe1f9'
  tertiary-fixed-dim: '#c3c5dc'
  on-tertiary-fixed: '#171b2b'
  on-tertiary-fixed-variant: '#434658'
  background: '#fcf9f8'
  on-background: '#1b1c1c'
  surface-variant: '#e4e2e1'
typography:
  display-lg:
    fontFamily: Manrope
    fontSize: 48px
    fontWeight: '300'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Manrope
    fontSize: 32px
    fontWeight: '500'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Manrope
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 32px
  body-lg:
    fontFamily: Manrope
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Manrope
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-sm:
    fontFamily: Manrope
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 12px
  md: 24px
  lg: 48px
  xl: 80px
  gutter: 24px
  margin: 32px
---

## Brand & Style

The core personality of this design system is rooted in the poise and precision of dance. It prioritizes the "Art of Stillness"—using negative space to allow the library’s content to breathe, mirroring the way a dancer occupies a stage. The audience is comprised of professionals, students, and enthusiasts who value clarity and focus over visual noise.

The design style is **Minimalism** infused with **Tonal Layering**. It avoids the sterility of pure functionalism by introducing warmth through its accent palette and soft textures. The visual language is intentional, editorial, and quiet, ensuring that the motion within the video library remains the primary focus of the user experience.

## Colors

The palette is designed to be ethereal and low-fatigue. The primary accent is a refined **Champagne Gold**, used sparingly to draw attention to interactive elements and premium states. 

- **Surfaces:** A hierarchy of Whites and Light Grays (#FFFFFF to #F5F5F5) defines the architecture without creating jarring transitions.
- **Typography:** Harsh blacks are strictly avoided. A **Deep Charcoal** (#2D2D2D) provides high legibility while maintaining a softer, more sophisticated contrast against the light backgrounds.
- **Accents:** Champagne (#D9C5A3) is the signature color for highlights, active states, and call-to-actions, evoking a sense of quality and archival value.

## Typography

This design system utilizes **Manrope** for its balance of geometric modernism and humanist warmth. The typography scales emphasize "generous verticality," using ample line heights to ensure long-form descriptions of choreography are easy to digest.

- **Display & Headlines:** Use lighter weights (300-500) with slight negative letter spacing to create a premium, editorial feel.
- **Body Text:** Standardized at 16px to 18px for maximum accessibility, utilizing a charcoal tint to reduce eye strain.
- **Labels:** Small caps or increased letter spacing should be used for metadata (e.g., dance styles, durations) to distinguish them from narrative body text.

## Layout & Spacing

The layout philosophy follows a **Fixed Grid** model on desktop (12 columns, 1200px max-width) and a fluid model on mobile. The spacing rhythm is strictly based on an **8px linear scale**, favoring larger increments (48px+) between sections to maintain the minimalist aesthetic.

Whitespace is treated as a functional element—it separates different dance genres and archival collections without the need for heavy dividers. Grid containers should use 24px gutters to allow the visual rhythm of video thumbnails to remain unobstructed.

## Elevation & Depth

Depth in this design system is subtle and atmospheric. It avoids heavy dropshadows in favor of **Tonal Layers** and **Ambient Shadows**.

- **Surfaces:** Secondary content lives on a #F9F9F9 surface, while the primary canvas is #FFFFFF. 
- **Shadows:** Use a single, highly diffused shadow style: `0px 4px 20px rgba(45, 45, 45, 0.05)`. This creates a "lifted" effect for cards and modals without breaking the minimalist plane.
- **Borders:** Thin, 1px borders in a soft light gray (#EAEAEA) define inputs and container boundaries, providing structure where tonal changes are too subtle.

## Shapes

The shape language is defined by "Softened Precision." Elements are neither strictly rectangular nor overtly bubbly. 

A standard **8px (Level 2)** corner radius is applied to all primary components, including buttons, cards, and input fields. This radius provides a professional and modern look that feels approachable but disciplined. Larger containers, such as modal overlays, may scale up to 16px (rounded-lg) to emphasize their role as distinct, temporary surfaces.

## Components

- **Buttons:** Primary buttons use a solid Champagne Gold (#D9C5A3) background with white text. Secondary buttons use a 1px border of Champagne with Charcoal text. Padding is generous: 12px vertical, 24px horizontal.
- **Cards:** Library cards utilize a 1px #EAEAEA border and no shadow in their default state. On hover, they transition to a subtle ambient shadow and a slight 2px vertical lift.
- **Chips:** Used for dance categories (e.g., "Contemporary", "Ballet"). These should have a light gray background (#F2F2F2) and no border, using 4px rounded corners.
- **Input Fields:** Minimalist design with a 1px bottom border only in the default state, transitioning to a full 1px Champagne border on focus.
- **Video Playback Controls:** Icons should be thin-stroke (1.5px) and rendered in Charcoal or White depending on the overlay.
- **Lists:** Use 24px of vertical padding between items, separated by a hairline divider (#F5F5F5) that does not span the full width of the container.