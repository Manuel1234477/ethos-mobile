# @dynamicMemberLookup on ViewModels: decision

Tracks issue #498.

## Decision

Not adopted. `@dynamicMemberLookup` forwards property names through a subscript, so the compiler
no longer checks that a member exists. A typo compiles and fails at runtime. The view models
use plain properties, which are checked at compile time, and the verbosity it removes is small.
