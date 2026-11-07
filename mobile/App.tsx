import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, SafeAreaView, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import { StatusBar } from 'expo-status-bar';
import LocalLinux, { LocalLinuxStatus } from './LocalLinux';

const colors = { bg: '#080B12', panel: '#111827', line: '#263247', text: '#F3F6FC', muted: '#91A0B8', cyan: '#55D6FF', green: '#48D597', amber: '#F5BC58' };

export default function App() {
  const [status, setStatus] = useState<LocalLinuxStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('正在检查本地运行环境…');
  const [apiKey, setApiKey] = useState('');

  const refresh = useCallback(async () => {
    try {
      const next = await LocalLinux.getStatus();
      setStatus(next);
      setMessage(next.installed ? 'Linux 环境已安装' : '等待安装内置 Linux 环境');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '无法读取本地运行状态');
    }
  }, []);

  useEffect(() => { refresh(); SecureStore.getItemAsync('deepseek_api_key').then(value => { if (value) setApiKey(value); }); }, [refresh]);

  const install = async () => {
    setBusy(true);
    setMessage('正在安装内置 ARM64 Linux 环境…');
    try {
      const next = await LocalLinux.installRuntime('asset://runtime.tar.gz', 'embedded');
      setStatus(next);
      setMessage('内置 Linux 运行环境安装完成');
    } catch (error) { setMessage(error instanceof Error ? error.message : '安装运行环境失败'); }
    finally { setBusy(false); }
  };

  const start = async () => {
    setBusy(true);
    try {
      await SecureStore.setItemAsync('deepseek_api_key', apiKey.trim());
      await LocalLinux.startBot(apiKey.trim());
      await refresh();
    } catch (error) { setMessage(error instanceof Error ? error.message : '启动 Bot 失败'); }
    finally { setBusy(false); }
  };

  const stop = async () => {
    setBusy(true);
    try { await LocalLinux.stopBot(); await refresh(); }
    catch (error) { setMessage(error instanceof Error ? error.message : '停止失败'); }
    finally { setBusy(false); }
  };

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar style="light" />
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <View style={styles.logo}><Text style={styles.logoText}>DS</Text></View>
          <View style={{ flex: 1 }}><Text style={styles.eyebrow}>LOCAL AGENT PLATFORM</Text><Text style={styles.title}>DS Bot</Text></View>
          <View style={styles.pill}><View style={[styles.dot, { backgroundColor: status?.running ? colors.green : colors.amber }]} /><Text style={styles.pillText}>{status?.running ? '运行中' : '本地模式'}</Text></View>
        </View>

        <View style={styles.hero}>
          <Text style={styles.heroLabel}>ANDROID · ARM64 · NO ROOT</Text>
          <Text style={styles.heroTitle}>手机本地运行版</Text>
          <Text style={styles.heroDesc}>Linux ARM64 运行环境已内置在安装包中。首次使用时安装到应用私有目录，无需手动下载运行包。</Text>
          <View style={styles.statusLine}><View style={[styles.dot, { backgroundColor: status?.installed ? colors.green : colors.amber }]} /><Text style={styles.statusText}>{message}</Text></View>
        </View>

        <Text style={styles.section}>运行环境</Text>
        <View style={styles.card}>
          <InfoRow label="设备架构" value={status?.abi || '检测中…'} />
          <InfoRow label="Linux 环境" value={status?.installed ? '已安装' : '尚未安装'} good={!!status?.installed} />
          <InfoRow label="Bot 进程" value={status?.running ? '运行中' : '未启动'} good={!!status?.running} />
          <InfoRow label="安装位置" value="应用私有目录 /files/linux" last />
        </View>

        <Text style={styles.section}>Linux 运行包</Text>
        <View style={styles.card}>
          <Text style={styles.fieldLabel}>ARM64 Linux 环境已随 APK 内置，无需填写下载地址或校验值。</Text>
          <TouchableOpacity style={[styles.button, styles.secondary]} onPress={install} disabled={busy || !!status?.installed}>
            <Text style={styles.secondaryText}>{status?.installed ? 'Linux 环境已安装' : '安装内置 Linux 环境'}</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.section}>DeepSeek 配置</Text>
        <View style={styles.card}>
          <Text style={styles.fieldLabel}>DeepSeek API Key（加密保存在本机）</Text>
          <TextInput value={apiKey} onChangeText={setApiKey} secureTextEntry autoCapitalize="none" autoCorrect={false} placeholder="输入 DeepSeek API Key" placeholderTextColor="#63738C" style={styles.input} />
          <TouchableOpacity style={[styles.button, styles.secondary]} onPress={start} disabled={busy || !status?.installed || !apiKey.trim() || !!status?.running}>
            <Text style={styles.secondaryText}>启动本地 Bot</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.section}>控制台</Text>
        <View style={styles.card}>
          <Text style={styles.consoleTitle}>LOCAL RUNTIME</Text>
          <Text selectable style={styles.console}>{status?.lastOutput || '内置 Linux ARM64 运行环境就绪后，即可配置 DeepSeek API Key 并启动本地 Bot。'}</Text>
        </View>

        <TouchableOpacity style={[styles.button, styles.secondary]} onPress={refresh} disabled={busy}>
          {busy ? <ActivityIndicator color={colors.cyan} /> : <Text style={styles.secondaryText}>刷新运行状态</Text>}
        </TouchableOpacity>
        {status?.running && <TouchableOpacity style={[styles.button, styles.danger]} onPress={stop} disabled={busy}><Text style={styles.dangerText}>停止 Bot</Text></TouchableOpacity>}
        <Text style={styles.footer}>保留应用包名 io.github.fei2411.dsbot · Android 12+</Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function InfoRow({ label, value, good, last }: { label: string; value: string; good?: boolean; last?: boolean }) {
  return <View style={[styles.infoRow, !last && styles.rowBorder]}><Text style={styles.infoLabel}>{label}</Text><Text style={[styles.infoValue, good && { color: colors.green }]}>{value}</Text></View>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg }, content: { padding: 22, paddingBottom: 38 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 13, marginTop: 10, marginBottom: 26 },
  logo: { width: 48, height: 48, borderRadius: 15, backgroundColor: '#123449', borderWidth: 1, borderColor: '#28617B', alignItems: 'center', justifyContent: 'center' },
  logoText: { color: colors.cyan, fontWeight: '900', fontSize: 17 }, eyebrow: { color: colors.cyan, fontSize: 9, letterSpacing: 2, fontWeight: '700' }, title: { color: colors.text, fontSize: 24, fontWeight: '800', marginTop: 2 },
  pill: { flexDirection: 'row', alignItems: 'center', gap: 7, paddingHorizontal: 10, paddingVertical: 7, borderRadius: 20, backgroundColor: '#182333' }, pillText: { color: colors.muted, fontSize: 11 }, dot: { width: 7, height: 7, borderRadius: 5 },
  hero: { borderRadius: 22, padding: 20, backgroundColor: '#101D2D', borderWidth: 1, borderColor: '#24445B' }, heroLabel: { color: colors.cyan, fontSize: 10, letterSpacing: 1.5, fontWeight: '700' }, heroTitle: { color: colors.text, fontSize: 25, fontWeight: '800', lineHeight: 33, marginTop: 14 }, heroDesc: { color: colors.muted, fontSize: 13, lineHeight: 21, marginTop: 10 }, statusLine: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 20 }, statusText: { color: colors.amber, fontSize: 12 },
  section: { color: colors.text, fontSize: 15, fontWeight: '700', marginTop: 25, marginBottom: 11 }, card: { backgroundColor: colors.panel, borderRadius: 16, borderWidth: 1, borderColor: colors.line, paddingHorizontal: 15, paddingVertical: 5 }, infoRow: { minHeight: 47, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10 }, rowBorder: { borderBottomWidth: 1, borderBottomColor: '#202B3C' }, infoLabel: { color: colors.muted, fontSize: 12 }, infoValue: { color: colors.text, fontSize: 12, fontWeight: '600', flexShrink: 1, textAlign: 'right' },
  fieldLabel: { color: colors.muted, fontSize: 11, marginTop: 12, marginBottom: 7 }, input: { minHeight: 44, borderWidth: 1, borderColor: colors.line, borderRadius: 10, paddingHorizontal: 11, color: colors.text, fontSize: 12, backgroundColor: '#0B1220' },
  consoleTitle: { color: colors.cyan, fontSize: 10, letterSpacing: 1.5, marginTop: 12, marginBottom: 10, fontWeight: '700' }, console: { color: '#B5C3D8', fontSize: 11, lineHeight: 18, fontFamily: 'monospace', paddingBottom: 13 },
  button: { minHeight: 48, borderRadius: 13, alignItems: 'center', justifyContent: 'center', marginTop: 13 }, secondary: { backgroundColor: '#14283A', borderWidth: 1, borderColor: '#28516A' }, secondaryText: { color: colors.cyan, fontWeight: '700', fontSize: 13 }, danger: { backgroundColor: '#351C25', borderWidth: 1, borderColor: '#713344' }, dangerText: { color: '#FF9BAE', fontWeight: '700' }, footer: { color: '#63738C', fontSize: 10, textAlign: 'center', marginTop: 25 },
});
