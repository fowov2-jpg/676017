import React, { useRef, useEffect } from 'react';
import { View, Text, StyleSheet, FlatList, TouchableOpacity, SafeAreaView, Alert } from 'react-native';
import { useAgentStore } from '../store/useAgentStore';
import { AgentLoop } from '../agent/AgentLoop';
import { SandboxService } from '../services/SandboxService';
import { modelService } from '../services/ModelService';
import { ZipService } from '../services/ZipService';
import { AgentLogEntry } from '../types';

interface Props { onBack: () => void; }

export default function AgentScreen({ onBack }: Props) {
  const { currentProject, logs, isRunning, performance, addLog, clearLogs, setRunning, setResultZip, setProject } = useAgentStore();
  const loopRef = useRef<AgentLoop | null>(null);
  const listRef = useRef<FlatList>(null);

  useEffect(() => { if (logs.length > 0) listRef.current?.scrollToEnd({ animated: true }); }, [logs]);

  const startAgent = async () => {
    if (!currentProject) return;
    if (!modelService.isModelLoaded()) { Alert.alert('Сначала загрузите модель'); return; }
    clearLogs(); setRunning(true);
    const sandbox = new SandboxService(currentProject.sandboxPath);
    const loop = new AgentLoop({
      sandbox,
      userPrompt: currentProject.prompt,
      settings: performance,
      generate: async (messages, settings) => modelService.generate(messages, settings),
      onLog: (entry) => addLog(entry),
      onStep: () => {},
    });
    loopRef.current = loop;
    try {
      const result = await loop.run();
      addLog({ id: Date.now().toString(), timestamp: Date.now(), type: 'info', message: result.summary });
      const zipPath = await ZipService.packProject(currentProject.sandboxPath, `result_${currentProject.name.replace('.zip', '')}`);
      setResultZip(zipPath);
      setProject({ ...currentProject, status: 'finished' });
      Alert.alert('Готово', 'Агент завершил работу.\nРезультат запакован в ZIP.');
    } catch (e: any) {
      addLog({ id: Date.now().toString(), timestamp: Date.now(), type: 'error', message: e.message });
      Alert.alert('Ошибка', e.message);
    } finally { setRunning(false); loopRef.current = null; }
  };

  const stopAgent = () => { loopRef.current?.stop(); setRunning(false); };
  const renderLog = ({ item }: { item: AgentLogEntry }) => {
    const color = item.type === 'error' ? '#ff6b6b' : item.type === 'tool_call' ? '#6a9eff' : item.type === 'tool_result' ? '#7dcea0' : '#ccc';
    return <View style={styles.logItem}><Text style={[styles.logType, { color }]}>{item.type.toUpperCase()}</Text><Text style={styles.logMessage}>{item.message}</Text></View>;
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={onBack} disabled={isRunning}><Text style={[styles.back, isRunning && { opacity: 0.4 }]}>← Назад</Text></TouchableOpacity>
        <Text style={styles.title}>Агент</Text>
      </View>
      <FlatList ref={listRef} data={logs} keyExtractor={(item) => item.id} renderItem={renderLog} contentContainerStyle={{ padding: 12 }} ListEmptyComponent={<Text style={styles.empty}>Нажми «Запустить», чтобы начать</Text>} />
      <View style={styles.footer}>{isRunning ? <TouchableOpacity style={[styles.btn, styles.stopBtn]} onPress={stopAgent}><Text style={styles.btnText}>Остановить</Text></TouchableOpacity> : <TouchableOpacity style={styles.btn} onPress={startAgent}><Text style={styles.btnText}>Запустить агента</Text></TouchableOpacity>}</View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b0b0b' },
  header: { flexDirection: 'row', alignItems: 'center', padding: 16, gap: 16 },
  back: { color: '#6a9eff', fontSize: 16 }, title: { color: '#fff', fontSize: 20, fontWeight: '600' },
  empty: { color: '#666', textAlign: 'center', marginTop: 40 },
  logItem: { backgroundColor: '#141414', borderRadius: 10, padding: 12, marginBottom: 8 },
  logType: { fontSize: 11, fontWeight: '700', marginBottom: 4 }, logMessage: { color: '#ddd', fontSize: 13, lineHeight: 18 },
  footer: { padding: 16 }, btn: { backgroundColor: '#1f3a5f', borderRadius: 14, padding: 16, alignItems: 'center' }, stopBtn: { backgroundColor: '#5c1f1f' }, btnText: { color: '#fff', fontSize: 16, fontWeight: '600' },
});
