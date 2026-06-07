# Director Command Room Design

## Register

Product UI for a film-production workflow. The surface serves operators who need to turn novel text into screenplay scenes, inspect generation quality, and ship YAML for review.

## Visual Direction

The interface is a dark graphite production room, with one cinematic background asset, a central spatial story map, and compact task consoles around it. The first screen must read as a professional director command room, not as a generic AI dashboard.

## Product Home

The product home is a full-bleed cinematic entry screen. It uses a generated production-suite background, concise product copy, and direct navigation into the workbench. The H1 is the product name, while supporting copy explains the specific promise: converting long-form novel text into checkable, rewritable, exportable Scene-level screenplay assets. The home section is shorter than the viewport so the command room is always hinted below.

## Tokens

- `--studio-bg`: app background, near-black graphite.
- `--studio-panel`: primary panel surface.
- `--studio-panel-2`: secondary console surface.
- `--studio-line`: hairline separators and component borders.
- `--studio-text`: main readable text.
- `--studio-muted`: secondary text.
- `--signal-cyan`: current selection and active workflow.
- `--signal-amber`: warnings and pending work.
- `--signal-green`: completed and ready states.
- `--signal-red`: failure and blocking states.

## Component Rules

- Buttons use a consistent 8px radius, icon + verb-object labels where space allows, and visible disabled states.
- Panels are operational surfaces, not decorative cards. Each panel must expose status, action, or data.
- Badges always communicate source or status: real data, mock fallback, pending, warning, ready, or error.
- Scene selection is shared across the map, timeline, and inspector.

## Motion

- Motion is only for state changes, scene selection, panel transitions, and streaming feedback.
- Default duration: 150-250ms. Spatial map camera/mesh motion can run 300ms.
- `prefers-reduced-motion` disables transform-heavy transitions and 3D animation intensity.

## 3D Map

- Desktop and tablet use a full-width central canvas as the primary work surface.
- Nodes represent chapters, entities, events, and scenes. Scene nodes are clickable and update the inspector.
- Mobile switches to a 2D timeline to keep the workflow readable.

## Bans

- No purple-blue AI gradients.
- No glassmorphism as the default component language.
- No marketing hero layout.
- No nested card grids.
- No decorative motion that does not reflect workflow state.
