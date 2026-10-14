# GitHub Actions cloud build

1. Create a **private** GitHub repository called `visual-finder`.
2. Upload the **contents of the VisualFinder folder** to the repository root (not the outer folder itself). A Git client that supports pushing directories may be easier than GitHub mobile web upload.
3. Open Actions > Build Visual Finder APK > Run workflow, or push to `main`.
4. Open the successful run and download `VisualFinder-debug-apk` from Artifacts. Extract the ZIP to get the debug APK.
5. Install on Android after reviewing the permissions and enabling install from your chosen file manager/browser if prompted.

This is an unverified prototype. The build may fail due to code or dependency issues. Read the Actions log and fix failures before installing. No cloud AI keys or personal images should be committed to the repository.
