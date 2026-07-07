# Product Requirements Document (PRD)
## Working Title: [Product Name] — Android Task Agent (MVP)

**Version:** 0.2 (MVP scope — updated with overlay interaction model + backend stack)
**Status:** Pre-build
**Platform:** Android only (native)
**Prepared for:** Antigravity CLI build handoff

---

## 1. Vision

A privacy-first Android agent app that completes real-world tasks on the user's
behalf by operating other apps directly — starting narrowly with food ordering
across a small set of delivery apps. The long-term vision is a broader
multi-domain agent (shopping, search, email, calculations), but the MVP
deliberately scopes to one task category to prove reliability, trust, and
willingness to pay before expanding.

**Core differentiators:**
- No user data collection or sale — subscription-only monetization
- Zero cloud retention of any visual data (screenshots, if used at all, are
  processed transiently and never persisted server-side)
- User always manually enters/confirms payment details — the agent never
  stores or auto-submits payment information
- **Overlay-based interaction model** — the agent does not take over the
  screen with its own full-screen app UI during active use. Instead, it
  appears as a translucent system overlay (similar in interaction pattern
  to Android's Gemini Live assistant, triggered via a system gesture such
  as holding the power button) layered on top of whatever app is currently
  open, with the underlying app content remaining visible beneath it

---

## 2. Problem Statement

Ordering food (or completing similar repetitive app-based tasks) requires
manually opening an app, searching, comparing, selecting items, and checking
out — every time, with no memory of preference across sessions unless the
app itself tracks it (usually by collecting and monetizing user data). Users
who want convenience without surveillance currently have no real alternative.

---

## 3. Target User (MVP)

- Android users, comfortable granting Accessibility permissions to a
  trusted app
- Frequent food-delivery-app users (ordering 2+ times/week)
- Privacy-conscious segment: skeptical of Big Tech data practices, willing
  to pay a subscription to avoid ad-supported/data-monetized alternatives

---

## 4. MVP Scope

### 4.1 In Scope
- **Single task domain:** Food ordering
- **Supported apps (pick 2–3 for MVP):** Swiggy, Zomato, Zepto (or similar,
  to be finalized based on API/automation feasibility testing)
- **Core flow (overlay-based):**
  1. User triggers the agent via a system-level gesture (e.g. long-press
     power button, similar to Android's Gemini Live invocation) OR opens
     the app directly from its home screen to type a request
  2. A translucent overlay appears on top of the current foreground app
     (or home screen) — soft accent-colored glow traced along the left,
     right, and top edges of the display; underlying app content remains
     visible and only lightly dimmed
  3. User speaks or types their request (e.g. "order me a chicken biryani
     from the usual place under 400 rupees") — a small floating pill
     element (mic icon + live status text) shows this is being heard
  4. Overlay transitions to a "thinking" state (subtle pulse on the edge
     glow) while the agent interprets intent via cloud LLM reasoning
  5. Agent extracts the on-screen UI structure (Accessibility Tree —
     element labels, types, positions) from the target app — **not**
     raw screenshots, wherever the tree is sufficient
  6. Agent reasons over the structured UI data (sent to cloud model) to
     decide the next action
  7. Agent performs the action (tap, scroll, type) via Accessibility
     Service. During this "acting" state, the overlay recedes to a
     minimal footprint (quiet edge glow + small floating status pill,
     e.g. "Adding items to cart…") so the underlying app is clearly
     visible and legible — this is the primary state the user watches
  8. At checkout/payment, the overlay shifts to a distinct "handoff"
     treatment (e.g. intensified edge glow or top-edge accent line) and
     the floating pill instructs the user to complete payment manually
     in the underlying app; overlay dimming is removed entirely here so
     the user has full clarity
  9. User confirms payment completion via a control in the floating pill
  10. Overlay shows a brief, quiet completion state (small checkmark +
      confirmation text in the pill) and then auto-dismisses
- **General utility features bundled in MVP** (lower complexity, high
  perceived value, help justify subscription from day one):
  - Web search passthrough (returns a summarized answer)
  - Basic calculator/computation requests
  - Simple text generation (e.g. draft an email, user copies output —
    no autonomous email sending in MVP)

### 4.2 Explicitly Out of Scope (MVP)
- iOS (see technical rationale — no equivalent automation capability exists
  on iOS; would require a separate, much narrower App Intents-based product)
- Amazon/Flipkart/general e-commerce automation
- Autonomous email sending (draft-only in MVP)
- Any auto-fill or storage of payment card/UPI details
- Multi-app orchestration in a single request (e.g. "order food AND book a
  cab") — single-task requests only
- On-device/local LLM inference
- Cross-session memory of user preferences (may be considered post-MVP,
  with explicit opt-in and local-only storage)

---

## 5. Technical Architecture

### 5.1 Platform & Language
- **Native Kotlin**, Jetpack Compose for UI
- No cross-platform framework (Flutter/KMP/Swift-on-Android rejected —
  core value is deep OS-level integration with no shared logic across
  platforms; see Section 5.5)

### 5.2 Automation Layer
- **Android Accessibility Service** for reading UI hierarchy of target apps
  and injecting taps/gestures/text input
- Primary data extracted: Accessibility Tree (view hierarchy, text labels,
  bounds, clickable elements) — this is sent to the cloud model for
  reasoning, NOT raw screenshots, by default
- **Screenshot fallback:** only invoked if the Accessibility Tree is
  insufficient (e.g. custom-rendered UI elements, canvas-based views).
  When used:
  - Captured and held only in a local temp file
  - Sent to the cloud model for a single inference call
  - Temp file deleted immediately after the step completes
  - Never logged, cached, or retained server-side (zero data retention
    inference call)

### 5.3 Cloud Reasoning Layer
- Cloud LLM (e.g. Claude via Anthropic API) receives structured UI state
  + user request context, returns the next action (tap/type/scroll/done)
- Stateless per-step calls; full task context passed each time (no
  server-side session memory retained beyond the active task)

### 5.4 Payment Handling
- App never stores, autofills, or transmits payment details
- Agent stops at any screen requesting payment info and prompts the user
  to complete that step manually, then resumes on user confirmation

### 5.5 Overlay Interaction Layer (System-Level UI)
- Implemented using Android's `TYPE_APPLICATION_OVERLAY` window type
  (requires the "Display over other apps" special permission, requested
  explicitly during onboarding with a clear explanation screen)
- Overlay is a lightweight Compose-based floating view, NOT a full-screen
  Activity — the underlying foreground app must remain visible and
  interactive-looking beneath it at all times except during active
  automation steps
- Trigger mechanisms to support:
  - In-app "start" action (opening the app directly)
  - System-level gesture invocation (e.g. long-press power button or an
    Accessibility-Service-registered gesture) — evaluate feasibility of
    hooking the existing Android Assistant-invocation gesture vs. building
    a custom trigger (e.g. floating persistent bubble, similar to
    Messenger's chat heads) during Phase 0 spike, since directly
    overriding the system power-button assistant gesture may require
    the user to set the app as their default Assist app in Android
    settings
- Overlay states to implement as distinct visual/behavioral modes:
  1. Idle/ready (ambient edge glow only)
  2. Listening (voice input active, floating status pill visible)
  3. Thinking/processing (pulsing edge glow, no user input needed)
  4. Acting (minimal overlay footprint, underlying app fully visible,
     "Take over" control always accessible)
  5. Payment handoff (distinct accent treatment, underlying app fully
     undimmed, manual confirmation control)
  6. Complete (brief confirmation, then auto-dismiss)
- "Take over" control must be accessible during the Acting state at all
  times — tapping it pauses agent automation immediately and returns full
  control to the user without requiring the overlay to fully dismiss first
- Overlay must never fully obscure the underlying app's content in a way
  that prevents the user from seeing what the agent is doing (a core
  trust requirement, not just a design preference)

### 5.6 Backend Stack

**Runtime & API layer**
- Kotlin + Ktor (preferred for shared DTOs/models with the native Android
  client) — a thin orchestration layer, not a heavy framework. Its main
  job is: receive task context from the client, call the LLM reasoning
  API, return the next action, handle subscription/account state. Go
  with a minimal framework (e.g. Chi or Fiber) is an acceptable
  alternative if the team prefers Go.

**LLM reasoning**
- Anthropic API (Claude) for step-by-step action reasoning — stateless
  per-step calls; full task context (user request + current Accessibility
  Tree snapshot) passed with each call rather than relying on server-side
  conversation memory

**Database**
- PostgreSQL for account/subscription state, supported-app configuration,
  and any explicitly opt-in user preference data (post-MVP). Schema kept
  intentionally minimal — no long-term storage of task request content or
  UI/screenshot data server-side

**Ephemeral task/session state**
- Redis, used only to hold in-flight task state (current step, active
  task's UI snapshot) with a short TTL, expiring automatically — this is
  the mechanism that technically enforces the "zero retention" privacy
  commitment rather than relying on policy alone

**Billing**
- Google Play Billing Library for subscription purchases (required by
  Play Store policy for digital subscriptions); backend verifies purchase
  tokens server-side via the Play Developer API — no direct Stripe
  integration for in-app subscriptions

**Hosting/infra**
- Containerized (Docker) deployment on Fly.io, Railway, or Cloud
  Run/ECS — scale-to-zero-capable platforms preferred at MVP stage given
  low/unpredictable early call volume. No Kubernetes or microservices
  architecture at this stage — unjustified complexity for MVP scope

**Monitoring/observability**
- Sentry (or equivalent) for crash/error tracking
- Structured logging with a hard rule: no user request content, UI tree
  data, or screenshot data ever written to logs — this is a build
  requirement, not just a policy statement, since it's core to the
  product's trust claims and difficult to retrofit later

### 5.7 Why Not Cross-Platform
iOS sandboxing prohibits one app from reading/controlling another app's UI;
there is no App Store–compliant equivalent to Android's Accessibility
Service for general cross-app automation. Since the entire value proposition
depends on this OS-level capability, there is no meaningful shared business
logic to justify Flutter/KMP overhead. Android should be native Kotlin;
any future iOS product must be scoped separately around App Intents/Siri
Shortcuts (limited to cooperating apps only).

---

## 6. Privacy & Trust Commitments (Product Requirements, Not Just Marketing)

- No user data sold or shared with third parties, ever
- No advertising, no ad SDKs
- Screenshots (when used) never persist beyond the single inference call;
  local temp files deleted immediately after each step
- Accessibility Tree data used for reasoning is not logged or retained by
  default beyond active task execution
- Payment details are never captured, stored, or transmitted by the app
- Clear, plain-language privacy policy (no legal-jargon obfuscation)

---

## 7. Monetization

- **Subscription-only**, no free ad-supported tier, no data monetization
- Suggested MVP tiers (finalize with pricing research):
  - **Basic** — limited number of agent actions/month, food ordering only
  - **Pro** — higher/unlimited action volume, includes bundled utility
    features (search, calculator, draft-writing)
- No in-app purchases beyond subscription tiers

---

## 8. Success Metrics (MVP)

- Task success rate (agent completes food order without user intervention,
  excluding manual payment step) — target ≥90% on supported apps
- Subscription conversion rate from trial/waitlist users
- Month-2 retention rate of paying subscribers
- Average agent actions per user per week (engagement/frequency signal)
- Support ticket volume related to failed/incorrect orders (reliability
  signal — should trend down over releases)

---

## 9. Key Risks

- **UI fragility:** target apps updating their UI can silently break
  automation flows — requires ongoing maintenance and monitoring, not a
  one-time build
- **Play Store policy scrutiny:** Accessibility Service use for automation
  (rather than literal accessibility purposes) invites review; app
  description, permission justification, and privacy policy must be
  airtight and transparent
- **Liability on incorrect orders:** wrong item/quantity/address — needs
  clear ToS, confirmation screenshots/state shown to user before final
  submission wherever feasible
- **Inference cost per task:** cloud reasoning calls per step must be
  cost-modeled against subscription price to protect margins

---

## 10. Build Milestones (Suggested Phasing)

1. **Phase 0:** Accessibility Service proof-of-concept on a single app
   (e.g. Swiggy) — read UI tree, execute a scripted multi-step order
   flow manually triggered (no LLM yet)
2. **Phase 1:** Integrate cloud LLM reasoning loop to dynamically decide
   actions step-by-step instead of hardcoded flow
3. **Phase 2:** Build the overlay interaction layer (`TYPE_APPLICATION_OVERLAY`
   window, floating status pill, edge-glow states, "Take over" control)
   and wire it to the Phase 1 reasoning loop in place of any placeholder
   full-screen UI
4. **Phase 3:** Add 2nd/3rd supported app; generalize the reasoning
   prompt/action schema across apps
5. **Phase 4:** Add bundled utility features (search, calculator, draft
   writing)
6. **Phase 5:** Subscription/payment infrastructure (Google Play Billing),
   onboarding, permission-request UX (including overlay + Accessibility
   permission explainers), privacy policy, Play Store submission
7. **Phase 6:** Closed beta with waitlist users, iterate on reliability
   metrics before public launch

---

*End of PRD — v0.2*
