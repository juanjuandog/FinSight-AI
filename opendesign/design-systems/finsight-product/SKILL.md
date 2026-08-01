---
name: finsight-product
description: Product design system for the FinSight AI evidence-driven equity research workspace.
---

# FinSight Product Design System

Use this system for FinSight AI product surfaces: company research, AI analysis,
evidence retrieval, company events, and watchlists.

## Character

- Neutral, analytical, restrained, content-first.
- Search is the primary way into the product.
- Evidence and data freshness are visible at the point of every conclusion.
- Dense information is grouped by research question, not by backend subsystem.

## Non-negotiables

- Use the tokens in `tokens/colors_and_type.css`.
- Use IBM Plex Sans for interface and headings, and IBM Plex Mono for market data.
- Warm pearl white is the dominant surface; charcoal ink carries hierarchy.
- Champagne gold is the only product accent and should stay muted.
- Do not use green as a brand, action, navigation, focus, or decoration color.
- Red and green are reserved only for signed market movement and risk semantics.
- Restrict soft mint, blush, lavender, and gold gradients to the translucent
  navigation atmosphere; never place them behind dense research content.
- Use subtle translucency and blur only for navigation and sticky shell surfaces.
- Avoid oversized radii, decorative metrics, dark dashboards, and purple AI styling.
- Positive/negative movement must include a sign or label, never color alone.
- Interactive targets are at least 44px on touch layouts and have visible focus states.
- AI conclusions show evidence count, snapshot time, and confidence nearby.

## Layout

- Desktop uses a two-zone shell: a 248px translucent navigation rail and one
  fluid research canvas. Do not add a contextual right rail.
- The five mutually exclusive workspaces are 公司研究、AI 分析、证据来源、
  近期事件、关注列表.
- Company research shows identity, core quote, one close-price line, and essential
  metrics only. AI conclusions, events, evidence, and watchlists never duplicate
  inside it.
- Navigation stays quiet and persistent on desktop and becomes a drawer below 900px.
- Engineering details stay hidden from the primary user surface.
- Mobile collapses to one column without a persistent rail.

## Motion

- 160–240ms for hover and state changes.
- 280ms for panel transitions.
- Animate opacity and transform only.
- Respect `prefers-reduced-motion`.

## Single approved direction

- White-gold pearl is the only supported product theme.
- Do not reintroduce Finance Pop, Editorial, dark mineral, classic, or theme
  switcher variants.
- Create richness through information hierarchy, precise spacing, hairline
  borders, and the low-opacity sidebar aura rather than extra decoration.
