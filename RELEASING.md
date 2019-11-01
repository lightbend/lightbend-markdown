## Releasing

**NOTE** The release process might be this simple... or it might not.  Consider it WIP, watch your steps, etc.

1. In sbt, run `release cross`.
1. Tell it the release version
1. Tell it the next version
1. Confirm you want to push the git commits and tags
1. Set the version back to the released version e.g. `set version in ThisBuild := "1.8.0"`
1. Run `bintrayRelease`
