using System.Buffers.Binary;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed class SyncServer : IDisposable
{
    internal const int Port = 43183;
    internal const int MaxBytes = 2 * 1024 * 1024;
    private readonly JournalStore store;
    private readonly X509Certificate2 certificate;
    private readonly TcpListener listener = new(IPAddress.Any, Port);
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim capacity = new(8);
    private string token;
    internal string Fingerprint { get; }
    internal event Action? Synced;

    internal SyncServer(JournalStore store)
    {
        this.store = store;
        certificate = LoadCertificate();
        Fingerprint = Base64Url(SHA256.HashData(certificate.RawData));
        string path = Path.Combine(JournalStore.DirectoryPath, "pairing.key");
        if (!File.Exists(path)) File.WriteAllText(path, Base64Url(RandomNumberGenerator.GetBytes(24)));
        token = File.ReadAllText(path);
        if (token.Length != 32) throw new InvalidDataException("Code d’association du PC illisible.");
    }

    internal void Start()
    {
        listener.Start(12);
        _ = ListenAsync();
    }

    internal string PairingCode(string address) => $"pause-sync:1:{address}:{Port}:{Fingerprint}:{Volatile.Read(ref token)}";

    internal void Revoke()
    {
        string replacement = Base64Url(RandomNumberGenerator.GetBytes(24));
        string path = Path.Combine(JournalStore.DirectoryPath, "pairing.key");
        File.WriteAllText(path + ".tmp", replacement);
        File.Move(path + ".tmp", path, overwrite: true);
        Volatile.Write(ref token, replacement);
    }

    private async Task ListenAsync()
    {
        try
        {
            while (!lifetime.IsCancellationRequested)
            {
                TcpClient client = await listener.AcceptTcpClientAsync(lifetime.Token).ConfigureAwait(false);
                var remote = (IPEndPoint)client.Client.RemoteEndPoint!;
                if (!LocalAddress(remote.Address) || !capacity.Wait(0)) client.Dispose();
                else _ = HandleAsync(client, lifetime.Token);
            }
        }
        catch (OperationCanceledException) { }
        catch (ObjectDisposedException) { }
        catch (SocketException) when (lifetime.IsCancellationRequested) { }
    }

    private async Task HandleAsync(TcpClient client, CancellationToken stopping)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stopping);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));
        CancellationToken deadline = timeout.Token;
        try
        {
            using (client)
            using (var tls = new SslStream(client.GetStream()))
            {
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = certificate,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                }, deadline).ConfigureAwait(false);
                byte[] header = new byte[4];
                await tls.ReadExactlyAsync(header, deadline).ConfigureAwait(false);
                int length = BinaryPrimitives.ReadInt32BigEndian(header);
                if (length is < 2 or > MaxBytes) return;
                byte[] request = new byte[length];
                await tls.ReadExactlyAsync(request, deadline).ConfigureAwait(false);

                object result;
                bool success = false;
                try
                {
                    (result, success) = Process(request);
                }
                catch (Exception exception) when (exception is JsonException or InvalidOperationException or ArgumentException or IOException or InvalidDataException or FormatException or NullReferenceException)
                {
                    result = new { error = "invalid" };
                }

                byte[] body = JsonSerializer.SerializeToUtf8Bytes(result);
                if (body.Length > MaxBytes) body = """{"error":"size"}"""u8.ToArray();
                BinaryPrimitives.WriteInt32BigEndian(header, body.Length);
                await tls.WriteAsync(header, deadline).ConfigureAwait(false);
                await tls.WriteAsync(body, deadline).ConfigureAwait(false);
                if (success) Synced?.Invoke();
            }
        }
        catch (Exception exception) when (exception is IOException or SocketException or AuthenticationException or OperationCanceledException or ObjectDisposedException) { }
        finally
        {
            capacity.Release();
        }
    }

    private (object Result, bool Success) Process(byte[] request)
    {
        var root = JsonNode.Parse(request)?.AsObject() ?? throw new InvalidDataException();
        string? provided = root["token"]?.GetValue<string>();
        string expected = Volatile.Read(ref token);
        if (provided?.Length != expected.Length || !CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(provided), Encoding.ASCII.GetBytes(expected)))
            return (new { error = "pairing" }, false);

        if (root["v"]?.GetValue<int>() != 1) return (new { error = "version" }, false);
        bool extended = root["extended"]?.GetValue<bool>() == true;
        bool drafts = root["drafts"]?.GetValue<bool>() == true;
        bool trackers = root["trackers"]?.GetValue<bool>() == true;
        string? journalId = root["journalId"]?.GetValue<string>();
        var changes = new List<Change>();
        var keys = new HashSet<string>(StringComparer.Ordinal);
        void AddChanges(JsonArray items, Func<string, bool> supported)
        {
            foreach (JsonNode? item in items)
            {
                string key = item!["key"]!.GetValue<string>();
                if (key.Length > 60 || !supported(key) || !keys.Add(key))
                    throw new InvalidDataException();
                changes.Add(new Change(key, JournalStore.Normalize(key, item["before"]), JournalStore.Normalize(key, item["after"])));
            }
        }
        AddChanges(root["changes"]!.AsArray(), key => !JournalStore.IsExtendedKey(key) && !JournalStore.IsDraftKey(key) && !JournalTracker.IsKey(key));
        if (extended && root["extendedChanges"] is JsonArray additional) AddChanges(additional, JournalStore.IsExtendedKey);
        if (drafts && root["draftChanges"] is JsonArray draftChanges) AddChanges(draftChanges, JournalStore.IsDraftKey);
        if (trackers && root["trackerChanges"] is JsonArray trackerChanges) AddChanges(trackerChanges, JournalTracker.IsKey);

        MergeResult outcome = store.Merge(journalId, changes);
        if (outcome.DifferentJournal) return (new { error = "journal" }, false);
        if (outcome.State is null)
        {
            return (new { journalId = store.Snapshot().Id, conflicts = outcome.Conflicts.Select(item => new { key = item.Key, pc = item.Before, phone = item.After }) }, false);
        }
        IDictionary<string, JsonNode> entries = extended && drafts && trackers ? outcome.State.Entries : outcome.State.Entries
            .Where(entry => (extended || !JournalStore.IsExtendedKey(entry.Key))
                && (drafts || !JournalStore.IsDraftKey(entry.Key)) && (trackers || !JournalTracker.IsKey(entry.Key))).ToDictionary();
        int ready = drafts ? outcome.State.MealDrafts().Count(draft => MealEstimate.Read(draft)?.Foods.Length > 0) : 0;
        return (new { journalId = outcome.State.Id, extended, drafts, trackers, entries,
            estimationPc = new { propositions = ready } }, true);
    }

    internal static string[] Addresses()
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(net => net.OperationalStatus == OperationalStatus.Up && net.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(net => net.GetIPProperties().UnicastAddresses
                .Where(item => item.Address.AddressFamily == AddressFamily.InterNetwork && LocalAddress(item.Address))
                .Select(item => new
                {
                    Address = item.Address.ToString(),
                    Rank = net.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 0
                        : net.GetIPProperties().GatewayAddresses.Any() ? 1 : 2
                }))
            .OrderBy(item => item.Rank).Select(item => item.Address).Distinct().ToArray();
    }

    private static bool LocalAddress(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        byte[] bytes = address.GetAddressBytes();
        return bytes[0] == 10 || bytes[0] == 127 || bytes[0] == 192 && bytes[1] == 168
            || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31 || bytes[0] == 169 && bytes[1] == 254;
    }

    private static X509Certificate2 LoadCertificate()
    {
        string path = Path.Combine(JournalStore.DirectoryPath, "sync.pfx");
        if (!File.Exists(path))
        {
            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest("CN=Pause Sync", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
            using X509Certificate2 generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
            File.WriteAllBytes(path + ".tmp", generated.Export(X509ContentType.Pfx));
            File.Move(path + ".tmp", path);
        }
        return new X509Certificate2(path, (string?)null, X509KeyStorageFlags.UserKeySet);
    }

    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public void Dispose()
    {
        lifetime.Cancel();
        listener.Stop();
        certificate.Dispose();
        lifetime.Dispose();
    }
}
