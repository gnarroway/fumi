# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- **Breaking** Reimplemented internals as a wrapper of the Java client. This is to better prioritise performance
  as well as to keep up with changes to the monitoring ecosystem. 
  The public API is mostly the same except as documented  below
- the need to opt *in* to `:default-exports?` (instead of opt *out* via `:exclude-defaults`)
- custom collectors can be created directly with a function without reifying a protocol
- the Java client automatically adds _created samples and _total suffixes (to counter samples) for open tracing compatibility
  These are passed through as they are so the final output may be slightly different.


## [0.2.0-b3] 2020-08-11
### Fixed
- Correct naming of process_max_fds (#1)

## [0.2.0-b2] 2020-04-14
### Changed
- More efficient implementations of Summary/Histogram

## [0.2.0-b1] 2020-04-08
### Changed
- Swapped implementation of counter/gauge for up to 10x faster DoubleAdder

## 0.1.0-beta1 - 2020-04-05
### Added
- Initial release

[Unreleased]: https://github.com/gnarroway/hato/compare/v0.2.0-b3...HEAD
[0.3.0-b1]: https://github.com/gnarroway/hato/compare/v0.2.0-b3...v0.3.0-b1
[0.2.0-b3]: https://github.com/gnarroway/hato/compare/v0.2.0-b2...v0.2.0-b3
[0.2.0-b2]: https://github.com/gnarroway/hato/compare/v0.2.0-b1...v0.2.0-b2
[0.2.0-b1]: https://github.com/gnarroway/hato/compare/v0.1.0-beta1...v0.1.0-b1
