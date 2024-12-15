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

    /** Win 端的固定公钥 */
    public static readonly string WindowsPublicKey;

    /** RSA加密对象, 包含公钥和私钥 */
    private static readonly RSA Rsa = RSA.Create();

    static KeyHandler()
    {
        var rsaKeys = LoadRSAKeys();
        WindowsPublicKey = rsaKeys.PublicKey;
        Rsa.ImportRSAPrivateKey(rsaKeys.PrivateKey, out _); // 导入私钥到 RSA 加密对象
        Console.WriteLine("密钥对已初始化");
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
            using var connection = new SqliteConnection($"Data Source={DatabasePath}");
            connection.Open();
            var createTableCommand =
                new SqliteCommand(
                    $"CREATE TABLE IF NOT EXISTS {TableName} ( Id INTEGER PRIMARY KEY AUTOINCREMENT, PublicKey TEXT NOT NULL, PrivateKey BLOB NOT NULL )",
                    connection);
            createTableCommand.ExecuteNonQuery();
        }

        using var selectConnection = new SqliteConnection($"Data Source={DatabasePath}");
        selectConnection.Open();

        // 检查密钥对是否已经存在
        var selectCommand =
            new SqliteCommand($"SELECT PublicKey, PrivateKey FROM {TableName} LIMIT 1", selectConnection);
        using var reader = selectCommand.ExecuteReader();
        // 读取密钥对并解密
        if (reader.Read())
        {
            var encryptedPublicKey = reader.GetString(0);
            var encryptedPrivateKey = (byte[])reader["PrivateKey"];
            var publicKey = DecryptString(encryptedPublicKey);
            var privateKey = DecryptBytes(encryptedPrivateKey);
            return (publicKey, privateKey);
        }

        // 如果密钥对不存在时生成新的密钥对
        var rsa = new RSACryptoServiceProvider();
        var newPublicKey = Convert.ToBase64String(rsa.ExportSubjectPublicKeyInfo());
        var newPrivateKey = rsa.ExportRSAPrivateKey();

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

    /// <summary>
    /// 使用私钥进行签名
    /// </summary>
    /// <param name="data">需要进行签名的内容</param>
    /// <returns>经过 Base64 编码的 string 数据</returns>
    public static string SignData(string data)
    {
        var dataBytes = Encoding.UTF8.GetBytes(data);
        var signedBytes = Rsa.SignData(dataBytes, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return Convert.ToBase64String(signedBytes);
    }
}