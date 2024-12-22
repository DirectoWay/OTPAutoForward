using System.Security.Cryptography;
using System.Text;
using System;
using System.IO;
using System.Windows;
using Microsoft.Data.Sqlite;
using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.OpenSsl;
using Org.BouncyCastle.Security;
using Org.BouncyCastle.X509;

namespace OTPAutoForward.ServiceHandler
{
    public static class KeyHandler
    {
        /** 对称加密密钥 */
        private const string Key = "autoCAPTCHA-encryptedKey";

        /** 密钥对的文件存储路径 */
        private const string DatabasePath = "RSAKeyStorage.db";

        /** 存储密钥对的表名 */
        private const string TableName = "RSAKeys";

        private static readonly byte[] PrivateKey;

        /** Win 端的固定公钥 */
        public static readonly string WindowsPublicKey;

        private static NotifyIconHandler _notifyIconHandler;

        static KeyHandler()
        {
            var rsaKeys = LoadRSAKeys();
            PrivateKey = rsaKeys.PrivateKey;
            WindowsPublicKey = rsaKeys.PublicKey;
            Console.WriteLine("密钥对已初始化");
        }

        public static void SetNotifyIconHandler(NotifyIconHandler notifyIconHandler)
        {
            _notifyIconHandler = notifyIconHandler;
        }

        /// <summary>
        /// 从数据库加载公钥和私钥 密钥对不存在时则生成新密钥对
        /// </summary>
        /// <returns>string 类型的公钥和 byte[] 类型的私钥</returns>
        private static (string PublicKey, byte[] PrivateKey) LoadRSAKeys()
        {
            // 检查数据库文件和数据表是否已经存在
            if (!File.Exists(DatabasePath))
            {
                using (var connection = new SqliteConnection($"Data Source={DatabasePath}"))
                {
                    connection.Open();
                    var createTableCommand =
                        new SqliteCommand(
                            $"CREATE TABLE IF NOT EXISTS {TableName} ( Id INTEGER PRIMARY KEY AUTOINCREMENT, PublicKey TEXT NOT NULL, PrivateKey BLOB NOT NULL )",
                            connection);
                    createTableCommand.ExecuteNonQuery();
                }
            }

            using (var selectConnection = new SqliteConnection($"Data Source={DatabasePath}"))
            {
                selectConnection.Open();

                // 检查密钥对是否已经存在
                var selectCommand =
                    new SqliteCommand($"SELECT PublicKey, PrivateKey FROM {TableName} LIMIT 1", selectConnection);

                using (var reader = selectCommand.ExecuteReader())
                {
                    // 读取密钥对并解密
                    if (reader.Read())
                    {
                        var encryptedPublicKey = reader.GetString(0);
                        var encryptedPrivateKey = (byte[])reader["PrivateKey"];
                        var publicKey = DecryptString(encryptedPublicKey);
                        var privateKey = DecryptBytes(encryptedPrivateKey);
                        return (publicKey, privateKey);
                    }
                }

                // 如果密钥对不存在时生成新的密钥对
                var keyGenerationParameters = new KeyGenerationParameters(new SecureRandom(), 2048);
                var keyPairGenerator = new RsaKeyPairGenerator();
                keyPairGenerator.Init(keyGenerationParameters);
                var keyPair = keyPairGenerator.GenerateKeyPair();
                var publicKeyGen = (RsaKeyParameters)keyPair.Public;
                var privateKeyGen = (RsaKeyParameters)keyPair.Private;

                var publicKeyInfo = SubjectPublicKeyInfoFactory.CreateSubjectPublicKeyInfo(publicKeyGen);
                var serializedPublicBytes = publicKeyInfo.ToAsn1Object().GetDerEncoded();
                var newPublicKey = Convert.ToBase64String(serializedPublicBytes);

                // 把 RsaKeyParameters 格式的私钥格式化成 byte[]
                byte[] newPrivateKey;
                using (var privateKeyStream = new MemoryStream())
                {
                    var privateKeyPemWriter = new PemWriter(new StreamWriter(privateKeyStream));
                    privateKeyPemWriter.WriteObject(privateKeyGen);
                    privateKeyPemWriter.Writer.Flush();
                    newPrivateKey = privateKeyStream.ToArray();
                }

                // 保存加密后的密钥对
                var encryptedNewPublicKey = EncryptString(newPublicKey);
                var encryptedNewPrivateKey = EncryptBytes(newPrivateKey);
                var insertCommand = new SqliteCommand(
                    $"INSERT INTO {TableName} (PublicKey, PrivateKey) VALUES (@PublicKey, @PrivateKey)",
                    selectConnection
                );
                insertCommand.Parameters.AddWithValue("@PublicKey", encryptedNewPublicKey);
                insertCommand.Parameters.AddWithValue("@PrivateKey", encryptedNewPrivateKey);
                insertCommand.ExecuteNonQuery();

                return (newPublicKey, newPrivateKey);
            }
        }

        /** 删除数据库中的公钥和私钥 */
        public static void DeleteRSAKeys()
        {
            var result = MessageBox.Show("确定要重置密钥吗？重置密钥后需要重新在 App 中进行配对\n如果您的程序可以正常运行, 请不要重置密钥", "确认重置",
                MessageBoxButton.YesNo,
                MessageBoxImage.Warning);
            if (result == MessageBoxResult.No)
            {
                return;
            }

            try
            {
                using (var connection = new SqliteConnection($"Data Source={DatabasePath}"))
                {
                    connection.Open();
                    var deleteCommand = new SqliteCommand($"DELETE FROM {TableName}", connection);
                    deleteCommand.ExecuteNonQuery();
                }

                var restartResult = MessageBox.Show("已经重置密钥! 程序将会自动重启, 请您在稍后在 App 端中进行重新配对", "重置密钥成功",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
                if (restartResult == MessageBoxResult.OK)
                {
                    RestartApplication();
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"重置密钥异常：{ex.Message}", "错误", MessageBoxButton.OK, MessageBoxImage.Error);
                Console.WriteLine($"删除密钥对时发生异常: {ex.Message}");
            }
        }

        /** 重启程序 */
        private static void RestartApplication()
        {
            _notifyIconHandler.Dispose();
            // 获取当前进程路径
            var processModule = System.Diagnostics.Process.GetCurrentProcess().MainModule;
            if (processModule != null)
            {
                var fileName = processModule.FileName;
                var startInfo = new System.Diagnostics.ProcessStartInfo(fileName)
                    { UseShellExecute = true, CreateNoWindow = true };
                // 启动新实例
                System.Diagnostics.Process.Start(startInfo);
            }

            // 关闭当前实例
            Application.Current.Shutdown();
        }

        /// <summary>
        /// String 类型的 AES 加密方法 可用于加密公钥或二维码内容
        /// </summary>
        /// <param name="plainText">用于对称加密的原文</param>
        public static string EncryptString(string plainText)
        {
            using (var aes = Aes.Create())
            {
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = Encoding.UTF8.GetBytes(Key);
                aes.IV = new byte[16]; // 初始化向量
                var encryptor = aes.CreateEncryptor(aes.Key, aes.IV);

                using (var ms = new MemoryStream())
                {
                    using (var cs = new CryptoStream(ms, encryptor, CryptoStreamMode.Write))
                    {
                        using (var sw = new StreamWriter(cs))
                        {
                            sw.Write(plainText);
                        }
                    }

                    return Convert.ToBase64String(ms.ToArray());
                }
            }
        }

        /// <summary>
        /// String 类型的 AES 解密方法 可用于解密公钥或二维码内容
        /// </summary>
        /// <param name="cipherText">经过对称加密后的密文</param>
        public static string DecryptString(string cipherText)
        {
            try
            {
                using (var aes = Aes.Create())
                {
                    aes.Mode = CipherMode.CBC;
                    aes.Padding = PaddingMode.PKCS7;
                    aes.Key = Encoding.UTF8.GetBytes(Key);
                    aes.IV = new byte[16]; // 初始化向量
                    // Base64 解码
                    var cipherBytes = Convert.FromBase64String(cipherText);

                    // 解密
                    using (var ms = new MemoryStream(cipherBytes))
                    {
                        using (var cs = new CryptoStream(ms, aes.CreateDecryptor(aes.Key, aes.IV),
                                   CryptoStreamMode.Read))
                        {
                            // 以字节流方式读取解密后的数据
                            using (var resultStream = new MemoryStream())
                            {
                                cs.CopyTo(resultStream);
                                return Encoding.UTF8.GetString(resultStream.ToArray());
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"解密失败: {ex.Message}");
                throw;
            }
        }

        /// <summary>
        /// Byte[] 类型的 AES 加密方法 可用于加密私钥
        /// </summary>
        /// <param name="plainBytes">用于对称加密的原字节数组</param>
        private static byte[] EncryptBytes(byte[] plainBytes)
        {
            using (var aes = Aes.Create())
            {
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = Encoding.UTF8.GetBytes(Key);
                aes.IV = new byte[16]; // 初始化向量

                using (var ms = new MemoryStream())
                {
                    using (var cs = new CryptoStream(ms, aes.CreateEncryptor(aes.Key, aes.IV),
                               CryptoStreamMode.Write))
                    {
                        cs.Write(plainBytes, 0, plainBytes.Length);
                        cs.FlushFinalBlock();
                        return ms.ToArray();
                    }
                }
            }
        }

        /// <summary>
        /// Byte[] 类型的 AES 解密方法 可用于解密私钥
        /// </summary>
        /// <param name="cipherBytes">经过对称加密后的字节数组</param>
        private static byte[] DecryptBytes(byte[] cipherBytes)
        {
            try
            {
                using (var aes = Aes.Create())
                {
                    aes.Mode = CipherMode.CBC;
                    aes.Padding = PaddingMode.PKCS7;
                    aes.Key = Encoding.UTF8.GetBytes(Key);
                    aes.IV = new byte[16]; // 初始化向量

                    using (var ms = new MemoryStream(cipherBytes))
                    {
                        using (var cs = new CryptoStream(ms, aes.CreateDecryptor(aes.Key, aes.IV),
                                   CryptoStreamMode.Read))
                        {
                            using (var resultStream = new MemoryStream())
                            {
                                cs.CopyTo(resultStream);
                                return resultStream.ToArray();
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Console.WriteLine(e);
                throw;
            }
        }

        /// <summary>
        /// 使用私钥进行签名
        /// </summary>
        /// <param name="data">需要进行签名的内容</param>
        /// <returns>经过 Base64 编码的 string 数据</returns>
        public static string SignData(string data)
        {
            var privateKey = ConvertToCipherParameters(PrivateKey);

            var dataBytes = Encoding.UTF8.GetBytes(data);

            // 使用私钥进行签名
            ISigner signer = new RsaDigestSigner(new Org.BouncyCastle.Crypto.Digests.Sha256Digest());
            signer.Init(true, privateKey);
            signer.BlockUpdate(dataBytes, 0, dataBytes.Length);
            var signature = signer.GenerateSignature();

            return Convert.ToBase64String(signature);
        }

        /** 把私钥转换回 ICipherParameters 格式 */
        private static ICipherParameters ConvertToCipherParameters(byte[] privateKeyBytes)
        {
            using (var reader = new StreamReader(new MemoryStream(privateKeyBytes)))
            {
                var pemReader = new PemReader(reader);
                var keyPair = (AsymmetricCipherKeyPair)pemReader.ReadObject();
                return keyPair.Private;
            }
        }
    }
}