# Pull request

## What this changes

<!-- One or two sentences. Link the issue it fixes, if there is one. -->

## Why

<!-- The problem, in terms of what a player observes. -->

## Checklist

- [ ] `./gradlew test` passes (L1 - the pure scheduling assertions)
- [ ] `./gradlew build` passes
- [ ] The change does **not** split a single tick's budget between orders (see the README's guarantees)
- [ ] If it touches a Mixin, it adds no `@Overwrite` (coexistence with other AE2 addons depends on this)
- [ ] If it changes observable behaviour, the README / CHANGELOG is updated
- [ ] If it changes a scheduling decision, the reasoning is in a comment or commit message - "why" is
      worth more here than "what", because the non-obvious parts of this codebase are all like that

## Testing done

<!--
  Say how you verified it. Note that the L1 tests cover the pure policy logic only; anything touching
  the AE2 hooks (tick, admission, insert) needs an actual game. If you ran one, say what you saw -
  e.g. "/schedulercore schedstate showed both orders advancing" or "cancelled one of two orders and
  the materials came back".
-->
