# Roblox VR Android / NEXA XR Bridge

Experimental Android XR compatibility project for phone + VRBox research.

This repository does **not** redistribute the proprietary Roblox Quest APK or Meta proprietary components.

Goals:
- Android XR/VRBox runtime experiments
- 6DoF head pose routing
- hand/controller pose routing
- Roblox Android detection and experimental OpenXR/Quest-compatible launch gating
- APK CI with diagnostics

Important: an APK built from this repository is a compatibility/runtime prototype. It does not by itself turn the normal Roblox Android client into the Quest VR client. Roblox VR only works if the Roblox client/runtime combination on the device actually exposes and accepts the required XR interfaces.
