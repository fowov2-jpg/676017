import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, SafeAreaView, ActivityIndicator, Alert, ScrollView } from 'react-native';
import DocumentPicker from 'react-native-document-picker';
import { ZipService } from '../services/ZipService';
import { useAgentStore } from '../store/useAgentStore';

interface Props { onBack: () => void; onStartAgent: () => void; }

export default function ProjectScreen({ onBack, onStartAgent }: Props) {
  const [loading, setLoading] = useState(false);
  const { currentProject, setProject, clearLogs } = useAgentStore();
  const pickZip = async () => {
    try {
      const res = await DocumentPicker.pickSingle({ type: [DocumentPicker.types.zip, DocumentPicker.types.allFiles], copyTo: 'cachesDirectory' });
      if (!res.fileCopyUri && !res.uri) { Alert.alert('Ошибка', 'Не удалось получить файл'); return; }
      setLoading(true); clearLogs();
      const zipPath = res.fileCopyUri || res.uri;
      const { sandboxPath, prompt, fileCount, projectId } = await ZipService.extractProject(zipPath);
      setProject({ id: projectId, name: res.name || 'project.zip', sandboxPath, prompt, fileCount, createdAt: Date.now(), status: 'idle' });
      Alert.alert('Готово', `Проект распакован\nФайлов: ${fileCount}`);
    } catch (e: any) {
      if (!DocumentPicker.isCancel(e)) Alert.alert('Ошибка', e.message || 'Не удалось открыть ZIP');
    } finally { setLoading(false); }
  };
  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}><TouchableOpacity onPress={onBack}><Text style={styles.back}>← Назад</Text></TouchableOpacity><Text style={styles.title}>Проект</Text></View>
      <ScrollView contentContainerStyle={styles.content}>
        <TouchableOpacity style={styles.pickButton} onPress={pickZip} disabled={loading}>
          {loading ? <ActivityIndicator color="#fff" /> : <><Text style={styles.pickText}>Выбрать ZIP файл</Text><Text style={styles.pickHint}>Архив будет распакован в песочницу</Text></>}
        </TouchableOpacity>
        {currentProject && <View style={styles.infoCard}>
          <Text style={styles.infoTitle}>{currentProject.name}</Text><Text style={styles.infoLine}>Файлов: {currentProject.fileCount}</Text><Text style={styles.infoLine}>ID: {currentProject.id.slice(0, 8)}…</Text>
          <Text style={styles.promptLabel}>Промпт:</Text><Text style={styles.promptText} numberOfLines={8}>{currentProject.prompt}</Text>
          <TouchableOpacity style={styles.startButton} onPress={onStartAgent}><Text style={styles.startText}>Запустить агента</Text></TouchableOpacity>
        </View>}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b0b0b' }, header: { flexDirection: 'row', alignItems: 'center', padding: 16, gap: 16 }, back: { color: '#6a9eff', fontSize: 16 }, title: { color: '#fff', fontSize: 20, fontWeight: '600' }, content: { padding: 16 },
  pickButton: { backgroundColor: '#1a1a1a', borderRadius: 16, padding: 24, alignItems: 'center', borderWidth: 1, borderColor: '#333' }, pickText: { color: '#fff', fontSize: 17, fontWeight: '600' }, pickHint: { color: '#777', fontSize: 13, marginTop: 6 },
  infoCard: { marginTop: 20, backgroundColor: '#141414', borderRadius: 16, padding: 18, borderWidth: 1, borderColor: '#2a2a2a' }, infoTitle: { color: '#fff', fontSize: 18, fontWeight: '600', marginBottom: 8 }, infoLine: { color: '#999', fontSize: 14, marginBottom: 4 }, promptLabel: { color: '#6a9eff', fontSize: 14, marginTop: 14, marginBottom: 6 }, promptText: { color: '#ccc', fontSize: 13, lineHeight: 19 }, startButton: { marginTop: 20, backgroundColor: '#1f3a5f', borderRadius: 12, padding: 14, alignItems: 'center' }, startText: { color: '#fff', fontSize: 16, fontWeight: '600' },
});
