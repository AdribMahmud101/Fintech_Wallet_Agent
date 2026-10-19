# K24 shared component requests

K24 uses the merged K23 components and tokens unchanged. Two diagram primitives would be useful
as future additions to the shared library:

- A labelled ring accepting nullable integer basis points, an accessible text description, and a
  static unknown track. Money division must happen with BigInteger before drawing coordinates.
- A connected vertical flow with readable node labels and descriptions on narrow screens / large
  fonts. K24 needs six Agent nodes and five Safety nodes; no horizontal clipping.

Until shared versions are available, the screen-local implementations live in
`ui/agent/K24Primitives.kt` (`EvidenceRing`, `ConnectedSteps`). They consume Fusion tokens and do
not replace any existing design-system component. No `ui/theme` or `ui/components` edits.
