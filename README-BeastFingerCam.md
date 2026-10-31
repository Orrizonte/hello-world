# Beast Fingers (v0.9)

Application Android : contours transparents des doigts au-dessus du clavier du téléphone, vus par la caméra des VITURE Beast. L'écran affiché dans les lunettes est le miroir du téléphone.

## Fonctionnement

1. **Caméra** : la caméra pass-through des Beast (USB `0C45:6368`) est lue en UVC standard avec libusb + libuvc. Aucun SDK propriétaire n'est nécessaire. Si `libglasses.so` est ajouté localement (`app/src/main/jniLibs/arm64-v8a/`, jamais committé), il sert de secours.
2. **Écran du téléphone** (`ScreenTracker`) :
   - Acquisition : composantes lumineuses à plusieurs seuils, assemblage des morceaux d'un bord coupé par les doigts, puis quadrilatère convexe.
   - Suivi image par image : recherche des bords clairs vers sombres, droites robustes, coins au sous-pixel.
   - Un cadre de repérage fin, dessiné sur le bord de l'écran, rend ce suivi fiable même en thème sombre.
3. **Doigts** (`FingerSegmenter`) : l'image caméra est redressée dans l'espace de l'écran par homographie, ce qui neutralise les mouvements de tête. Chaque cellule est classée en fusionnant trois indices :
   - un fond appris en continu, avec compensation d'exposition ;
   - un modèle de peau adaptatif, appris sur le squelette MediaPipe ;
   - un a priori spatial (capsules autour des os de la main).

   Puis viennent un lissage temporel et spatial et un filtrage des composantes : un doigt entre toujours par un bord de l'écran. Les contours sont extraits par marching squares au sous-pixel.
4. **Affichage** (`FingerOverlayView`) : une surcouche plein écran non tactile, hébergée par un service d'accessibilité (`TYPE_ACCESSIBILITY_OVERLAY`). Une surcouche ordinaire passe sous le clavier. Le dessin est refait à chaque vsync, avec une prédiction de mouvement.

## Compilation

GitHub Actions (`.github/workflows/build-beast-fingercam.yml`) : `scripts/prepare-mediapipe.sh` puis `scripts/build-apk.sh`. L'APK non signé et le journal sont publiés sur la branche `ci-output`.

Banc de test hors appareil (images synthétiques, IoU des contours) : `tools/SyntheticBench.java`.
