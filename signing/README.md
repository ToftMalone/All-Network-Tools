# Signature des APK de test

`debug.keystore` est la clé de debug Android standard (alias `androiddebugkey`, mot de passe `android`).
Elle n'est **pas secrète** : elle sert à signer les APK de test pour que les mises à jour s'installent
par-dessus les précédentes. Ne l'utilisez pas pour une publication sur le Play Store.
