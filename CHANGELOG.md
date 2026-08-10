# Changelog

## [0.1.2](https://github.com/anatolykoptev/oxpulse-android/compare/v0.1.1...v0.1.2) (2026-08-10)


### Fixed

* **ble:** synchronize GATT server state against binder-thread callbacks ([#34](https://github.com/anatolykoptev/oxpulse-android/issues/34)) ([68f2bd3](https://github.com/anatolykoptev/oxpulse-android/commit/68f2bd3eb7fe9652ae35d2c1d6f250f64c5ade5d))
* **ci:** run unit tests, fail on missing git/keystore, fix test package name ([#31](https://github.com/anatolykoptev/oxpulse-android/issues/31)) ([dfc15fb](https://github.com/anatolykoptev/oxpulse-android/commit/dfc15fbb6a8e38779ab2d0658b1959b2f3a957bd))
* FGS restart policy, null system service logging, CAP_SERVER_URL guard, and more ([#36](https://github.com/anatolykoptev/oxpulse-android/issues/36)) ([bb1b58a](https://github.com/anatolykoptev/oxpulse-android/commit/bb1b58a11bfdc10d5a5e385c3a28cdfc8bc24d24))
* **fgs:** synchronize state mutations + rollback on partial acquisition failure ([#33](https://github.com/anatolykoptev/oxpulse-android/issues/33)) ([5f042a5](https://github.com/anatolykoptev/oxpulse-android/commit/5f042a5c3f5bb01bd8a8e83cc5d41f447ce5125e))
* **lifecycle:** add handleOnDestroy() to all Capacitor plugins ([#32](https://github.com/anatolykoptev/oxpulse-android/issues/32)) ([4de1f41](https://github.com/anatolykoptev/oxpulse-android/commit/4de1f416674cdc533c72723eeec0c59064064287))
* **network:** graceful degradation when validated callback budget exceeded ([#35](https://github.com/anatolykoptev/oxpulse-android/issues/35)) ([8b71c8f](https://github.com/anatolykoptev/oxpulse-android/commit/8b71c8feb18bfe6495adc731d1d9ff629164b680))
* SecurityException catch in startNetworkMonitor + remove unused deps ([#37](https://github.com/anatolykoptev/oxpulse-android/issues/37)) ([de8481a](https://github.com/anatolykoptev/oxpulse-android/commit/de8481a0eb478a92c561731ace61e94287982946))
