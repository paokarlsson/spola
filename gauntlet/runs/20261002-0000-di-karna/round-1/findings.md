# Findings — round 1

VERDICT: PASS
Counts: 0 blocker, 0 major, 7 minor (judge skipped: all three critics gave PASS with no BLOCKER/MAJOR)

## MINOR (not blocking)
- BRK-1: The ScopedValue guard against undeclared `Container.get` from a factory can be bypassed from a non-structured thread. ARCHITECTURE.md overstates the guarantee.
- BRK-2: Errors (e.g. AssertionError) thrown by factories are not wrapped in a ResolutionException with the dependency chain, contrary to the Factory Javadoc.
- BRK-3: Recursive resolution and validation overflow the stack on dependency chains around 1000 deep.
- AUD-1: In the resolver form, an undeclared `r.get(X)` is only caught at first creation, not at `Container.create`. This is documented.
- MNT-1: The "bound for that type" hint is duplicated in Container and Validator.
- MNT-2: The singleton-scope check and scope label are repeated inline. Suggest `Scope.isSingleton()`.
- MNT-3: The arity overloads in BindingBuilder repeat their Javadoc.
