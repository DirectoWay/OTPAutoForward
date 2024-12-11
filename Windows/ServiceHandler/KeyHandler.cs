using System.Security.Cryptography;
using System.Text;

namespace WinCAPTCHA.ServiceHandler;

using System;
using System.IO;
using Microsoft.Data.Sqlite;

public static class KeyHandler
{
    /** 对称加密密钥 */
    private const string Key = "autoCAPTCHA-encryptedKey";

    /** 密钥对的文件存储路径 */
    private const string DatabasePath = "RSAKeyStorage.db";

    /** 存储密钥对的表名 */
    private const string TableName = "RSAKeys";

    static KeyHandler()
    {
        // 初始化数据库
        if (File.Exists(DatabasePath)) return;

        using var connection = new SqliteConnection($"Data Source={DatabasePath}");
        connection.Open();
        var createTableCommand = new SqliteCommand(
            $@"CREATE TABLE IF NOT EXISTS {TableName} (
                        Id INTEGER PRIMARY KEY AUTOINCREMENT,
                        PublicKey TEXT NOT NULL,
                        PrivateKey BLOB NOT NULL
                    )",
            connection
        );
        createTableCommand.ExecuteNonQuery();
    }

    /** 保存公钥和私钥到本地数据库 */
    public static void SaveRSAKeys(string publicKey, byte[] privateKey)
    {
        // 加密公钥和私钥
        var encryptedPublicKey = EncryptString(publicKey);
        var encryptedPrivateKey = EncryptBytes(privateKey);

        using var connection = new SqliteConnection($"Data Source={DatabasePath}");
        connection.Open();
        var insertCommand = new SqliteCommand(
            $"INSERT INTO {TableName} (PublicKey, PrivateKey) VALUES (@PublicKey, @PrivateKey)",
            connection);
        insertCommand.Parameters.AddWithValue("@PublicKey", encryptedPublicKey);
        insertCommand.Parameters.AddWithValue("@PrivateKey", encryptedPrivateKey);
        insertCommand.ExecuteNonQuery();
    }

    /** 从数据库加载公钥和私钥 */
    public static (string? PublicKey, byte[]? PrivateKey) LoadRSAKeys()
    {
        using var connection = new SqliteConnection($"Data Source={DatabasePath}");
        connection.Open();
        var selectCommand = new SqliteCommand($"SELECT PublicKey, PrivateKey FROM {TableName} LIMIT 1", connection);
        using var reader = selectCommand.ExecuteReader();
        if (!reader.Read()) return (null, null); // 密钥对不存在

        var encryptedPublicKey = reader.GetString(0);
        var encryptedPrivateKey = reader.GetFieldValue<byte[]>(1);

        // 解密公钥和私钥
        var publicKey = DecryptString(encryptedPublicKey);
        var privateKey = DecryptBytes(encryptedPrivateKey);
        return (publicKey, privateKey);
    }

    /** 检查公钥和私钥是否已经存在 */
    public static bool CheckRSAKeys()
    {
        using var connection = new SqliteConnection($"Data Source={DatabasePath}");
        connection.Open();
        var countCommand = new SqliteCommand(
            $"SELECT COUNT(*) FROM {TableName}",
            connection
        );
        return Convert.ToInt32(countCommand.ExecuteScalar()) > 0;
    }

    /** 删除数据库中的公钥和私钥 */
    public static void DeleteRSAKeys()
    {
        using var connection = new SqliteConnection($"Data Source={DatabasePath}");
        connection.Open();
        var deleteCommand = new SqliteCommand(
            $"DELETE FROM {TableName}",
            connection
        );
        deleteCommand.ExecuteNonQuery();
    }

    /// <summary>
    /// String 类型的 AES 加密方法 可用于加密公钥或二维码内容
    /// </summary>
    /// <param name="plainText">用于对称加密的原文</param>
    public static string EncryptString(string plainText)
    {
        using var aes = Aes.Create();
        aes.Mode = CipherMode.CBC;
        aes.Padding = PaddingMode.PKCS7;
        aes.Key = Encoding.UTF8.GetBytes(Key);
        aes.IV = new byte[16]; // 初始化向量
        var encryptor = aes.CreateEncryptor(aes.Key, aes.IV);

        using var ms = new MemoryStream();
        using var cs = new CryptoStream(ms, encryptor, CryptoStreamMode.Write);
        using (var sw = new StreamWriter(cs))
        {
            sw.Write(plainText);
        }

        return Convert.ToBase64String(ms.ToArray());
    }

    /// <summary>
    /// String 类型的 AES 解密方法 可用于解密公钥或二维码内容
    /// </summary>
    /// <param name="cipherText">经过对称加密后的密文</param>
    public static string DecryptString(string cipherText)
    {
        try
        {
            using var aes = Aes.Create();
            aes.Mode = CipherMode.CBC;
            aes.Padding = PaddingMode.PKCS7;
            aes.Key = Encoding.UTF8.GetBytes(Key);
            aes.IV = new byte[16]; // 初始化向量

            // Base64 解码
            var cipherBytes = Convert.FromBase64String(cipherText);

            // 解密
            using var ms = new MemoryStream(cipherBytes);
            using var cs = new CryptoStream(ms, aes.CreateDecryptor(aes.Key, aes.IV), CryptoStreamMode.Read);

            // 以字节流方式读取解密后的数据
            using var resultStream = new MemoryStream();
            cs.CopyTo(resultStream);

            return Encoding.UTF8.GetString(resultStream.ToArray());
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
        using var aes = Aes.Create();
        aes.Mode = CipherMode.CBC;
        aes.Padding = PaddingMode.PKCS7;
        aes.Key = Encoding.UTF8.GetBytes(Key);
        aes.IV = new byte[16]; // 初始化向量

        using var ms = new MemoryStream();
        using var cs = new CryptoStream(ms, aes.CreateEncryptor(aes.Key, aes.IV), CryptoStreamMode.Write);
        cs.Write(plainBytes, 0, plainBytes.Length);
        cs.FlushFinalBlock();

        return ms.ToArray();
    }

    /// <summary>
    /// Byte[] 类型的 AES 解密方法 可用于解密私钥
    /// </summary>
    /// <param name="cipherBytes">经过对称加密后的字节数组</param>
    private static byte[] DecryptBytes(byte[] cipherBytes)
    {
        try
        {
            using var aes = Aes.Create();
            aes.Mode = CipherMode.CBC;
            aes.Padding = PaddingMode.PKCS7;
            aes.Key = Encoding.UTF8.GetBytes(Key);
            aes.IV = new byte[16]; // 初始化向量

            using var ms = new MemoryStream(cipherBytes);
            using var cs = new CryptoStream(ms, aes.CreateDecryptor(aes.Key, aes.IV), CryptoStreamMode.Read);

            using var resultStream = new MemoryStream();
            cs.CopyTo(resultStream);

            return resultStream.ToArray();
        }

        catch (Exception ex)
        {
            Console.WriteLine($"解密失败: {ex.Message}");
            throw;
        }
    }
}