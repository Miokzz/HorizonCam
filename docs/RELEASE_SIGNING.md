# Assinatura de release

O projeto **não contém nem coleta senha de keystore**. O GitHub Actions gera um APK debug assinado pela chave de testes do pipeline, não adequado a distribuição final.

Para produzir uma release verdadeiramente assinada, o proprietário deve, **localmente e com segurança**:

1. Criar uma chave privada com o `keytool` ou o assistente do Android Studio (`Build > Generate Signed App Bundle / APK`).
2. Guardar keystore, alias e senhas em ambiente privado, nunca commitar arquivo/password no repositório.
3. Definir signingConfig para `release` usando variáveis de ambiente ou `keystore.properties` local ignorado pelo git.
4. Compilar `gradle :app:assembleRelease`, verificar assinatura via `apksigner verify --print-certs`, e instalar em hardware.

Release assinado não é gerado automaticamente enquanto não houver chave do proprietário configurada.


Os APKs de teste atuais do workflow usam o secret de repositório `HORIZONCAM_TEST_KEYSTORE_V1` e uma chave exclusiva para builds de desenvolvimento. O certificado SHA-256 é `4b90c2ec8c6090ee53014eeedd9938fa337465b767677c496804487d5487a7af`. O pipeline verifica o certificado do app e do teste instrumentado antes de publicar. Alguns APKs anteriores usam certificados diferentes. O Android só aceita atualização direta quando o certificado coincide. A chave privada de teste fica fora do Git.
