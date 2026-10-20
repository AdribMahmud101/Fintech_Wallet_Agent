# K26 device-found polish

| Defect | Change | Verification |
| --- | --- | --- |
| 1. Home brand icons | Official Base in-product network mark; USDC circle within viewport and compact SVG arcs normalized to cubic curves for Android. Sources pinned in drawable/ICONS.md. | BrandVectorTest parses both vectors on JVM, checking viewport, paths, fill rules, foreground and circle geometry. |
| 2. Duplicate network badge | Removed the hero network row; top bar retains Base Sepolia · TEST. | Source check: no network badge in TreasuryHero; assembleDebug. |
