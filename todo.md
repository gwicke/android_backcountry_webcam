# Features
* Expose AV1 encoder options: CRF, encoding mode, GOP size (requires wrapper
  change)
* Option for disabling the capture
* Remote config checks
* Capture timestamp preservation - queue parallel to encoder; can also be used
  to bound in-flight frames in case of network trouble
* Low power mode based on battery status
  * reduce encoding cost
  * increase interval
  * stop uploading entirely
* Blob storage backends?

# Robustness
* Upload retry / error handling
