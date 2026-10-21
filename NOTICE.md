# Tono Kempo

App Android para cambiar el tono de audios en semitonos.

Usa:
- Rubber Band Library (GPL v2 o posterior) para cambiar el tono.
- Kempo (Apache-2.0), motor alterno de tono.
- ONNX Runtime (MIT) y el modelo HT-Demucs en formato ONNX (MIT, de StemSplit / Meta AI)
  para separar pistas. El modelo se descarga la primera vez desde Hugging Face.
- Beat This! (MIT, Institute of Computational Perception, JKU Linz) en formato ONNX para
  detectar tempo y golpes. El modelo se descarga la primera vez desde Hugging Face.

Por usar Rubber Band, este proyecto debe distribuirse bajo la GPL v2 o posterior
con su código fuente disponible. Agrega un archivo LICENSE con el texto de la GPL
(en GitHub: Add file > Create new file > nombre "LICENSE" > botón "Choose a license template").
