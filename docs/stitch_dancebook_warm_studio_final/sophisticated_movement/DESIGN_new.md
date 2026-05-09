---
name: Sophisticated Movement
colors:
  surface: '#fbf9f8'
  surface-dim: '#dbdad9'
  surface-bright: '#fbf9f8'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f5f3f3'
  surface-container: '#efeded'
  surface-container-high: '#e9e8e7'
  surface-container-highest: '#e4e2e2'
  on-surface: '#1b1c1c'
  on-surface-variant: '#4c463c'
  inverse-surface: '#303031'
  inverse-on-surface: '#f2f0f0'
  outline: '#7d766b'
  outline-variant: '#cfc5b9'
  surface-tint: '#6b5c41'
  primary: '#6b5c41'
  on-primary: '#ffffff'
  primary-container: '#d9c5a3'
  on-primary-container: '#605136'
  inverse-primary: '#d8c4a2'
  secondary: '#5f5e5e'
  on-secondary: '#ffffff'
  secondary-container: '#e2dfde'
  on-secondary-container: '#636262'
  tertiary: '#5e5e5c'
  on-tertiary: '#ffffff'
  tertiary-container: '#c9c7c4'
  on-tertiary-container: '#535351'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#f5e0bd'
  primary-fixed-dim: '#d8c4a2'
  on-primary-fixed: '#241a05'
  on-primary-fixed-variant: '#52452b'
  secondary-fixed: '#e5e2e1'
  secondary-fixed-dim: '#c8c6c5'
  on-secondary-fixed: '#1c1b1b'
  on-secondary-fixed-variant: '#474746'
  tertiary-fixed: '#e4e2df'
  tertiary-fixed-dim: '#c8c6c4'
  on-tertiary-fixed: '#1b1c1a'
  on-tertiary-fixed-variant: '#474745'
  background: '#fbf9f8'
  on-background: '#1b1c1c'
  surface-variant: '#e4e2e2'
typography:
  display-lg:
    fontFamily: Manrope
    fontSize: 48px
    fontWeight: '700'
    lineHeight: '1.1'
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Manrope
    fontSize: 32px
    fontWeight: '600'
    lineHeight: '1.2'
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Manrope
    fontSize: 24px
    fontWeight: '600'
    lineHeight: '1.3'
  body-lg:
    fontFamily: Manrope
    fontSize: 18px
    fontWeight: '500'
    lineHeight: '1.6'
  body-md:
    fontFamily: Manrope
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
  label-md:
    fontFamily: Manrope
    fontSize: 14px
    fontWeight: '600'
    lineHeight: '1.4'
    letterSpacing: 0.05em
  headline-lg-mobile:
    fontFamily: Manrope
    fontSize: 28px
    fontWeight: '600'
    lineHeight: '1.2'
  body-md-mobile:
    fontFamily: Manrope
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.5'
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  unit: 8px
  container-max: 1280px
  gutter: 24px
  margin-desktop: 64px
  margin-mobile: 20px
---

## Brand & Style

This design system is anchored in an editorial-inspired Minimalism that emphasizes intentionality, poise, and fluid motion. It is designed for premium SaaS, boutique fintech, or high-end lifestyle platforms where the user experience must feel curated rather than cluttered.

The aesthetic leans into the "Quiet Luxury" movement—utilizing generous whitespace, high-quality typography, and a restrained color palette to create an atmosphere of calm authority. Motion should be utilized as a functional layer, with eased transitions that mimic physical inertia, ensuring that the interface feels responsive yet dignified. The UI evokes a sense of trustworthy sophistication and effortless efficiency.

## Colors

The palette is centered on a foundation of "Champagne Gold" (#D9C5A3), used sparingly for high-impact accents, primary actions, and brand moments. 

- **Backgrounds:** Utilize a crisp, off-white or light parchment (#F7F5F2) to reduce harsh contrast and maintain an elegant warmth.
- **Typography:** Primary text is set in a deep charcoal (#1A1A1A) for maximum legibility, while secondary metadata uses a muted grey (#6B6B6B).
- **Accents:** The primary yellow (#D9C5A3) should be applied to interactive states, active indicators, and subtle decorative borders to draw the eye without overwhelming the composition.

## Typography

This system utilizes **Manrope** exclusively to maintain a modern, geometric, yet highly readable aesthetic. 

- **Weight Adjustments:** To enhance readability and visual hierarchy, body text has been promoted to Medium (500) or Regular (400) weights. This ensures text remains legible across various displays without appearing "anemic."
- **Headings:** All headings now utilize Semi-Bold (600) or Bold (700) weights. This creates a clear structural anchor for the user’s eye, contrasting against the lighter backgrounds.
- **Rhythm:** Generous line-heights (1.6 for body) are critical to maintaining the "Sophisticated Movement" narrative, allowing the content to breathe and preventing visual fatigue.

## Layout & Spacing

The layout follows a **fixed-center grid** for desktop to maintain an editorial feel, transitioning to a fluid model for tablet and mobile.

- **Grid:** A 12-column grid is standard for desktop, with elements often spanning 6 or 8 columns to maximize whitespace in the margins. 
- **Rhythm:** A strict 8px baseline grid ensures vertical harmony. Component padding should lean towards being oversized (e.g., 24px or 32px) rather than cramped.
- **Breakpoints:**
    - **Desktop (1280px+):** 64px side margins.
    - **Tablet (768px - 1279px):** 40px side margins, 8-column grid.
    - **Mobile (Up to 767px):** 20px side margins, 4-column grid.

## Elevation & Depth

This design system avoids heavy drop shadows in favor of **Tonal Layers** and **Low-Contrast Outlines**.

- **Surface Strategy:** Elevation is expressed through subtle shifts in background color (e.g., a card using a slightly brighter white than the background) rather than physical distance.
- **Shadows:** When necessary for functional depth (like menus or modals), use "Ambient Shadows"—extremely diffused (20-40px blur), low opacity (4-6%), and slightly tinted with the brand’s yellow or secondary charcoal to avoid a muddy look.
- **Borders:** Use soft 1px borders in a muted version of the accent color or a light grey to define boundaries without breaking the minimalist flow.

## Shapes

The shape language is **Soft (Level 1)**. This provides enough rounding to feel modern and approachable without losing the architectural precision of the design.

- **Standard Elements:** Buttons and input fields use a 0.25rem (4px) radius.
- **Containers:** Large cards or sections use a 0.5rem (8px) radius.
- **Icons:** Should follow a consistent stroke weight (1.5px to 2px) with slightly rounded terminals to match the typography.

## Components

- **Buttons:** Primary buttons use a solid #D9C5A3 fill with #1A1A1A text. Use a subtle scale-down effect (0.98) on click to simulate tactile feedback.
- **Input Fields:** Use a minimal bottom-border style or a very light-filled background with no border, becoming more defined on focus with a 1px #D9C5A3 outline.
- **Chips:** Small, pill-shaped tags with a light #D9C5A3 (15% opacity) background and #1A1A1A text for categorizing content.
- **Cards:** No heavy borders. Use a change in surface tone or a very soft 1px #E5E5E5 border. Ensure padding inside cards is at least 32px to maintain the elegant spacing.
- **Navigation:** A persistent, high-blur (Glassmorphism) header that allows the content to move elegantly beneath it, keeping the primary navigation always within reach but visually unobtrusive.