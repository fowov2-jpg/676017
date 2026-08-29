import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet, FlatList, TouchableOpacity, ActivityIndicator, SafeAreaView, Alert } from 'react-native';
import { modelService, ModelInfo } from '../services/ModelService';
import { useAgentStore } from '../store/useAgentStore';

interface Props { onBack: () => void; onModelLoaded: () => void; }

export default function ModelScreen({ onBack, onModelLoaded }: Props) {
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingModel, setLoadingModel] = useState<string | null>(null);
  const performance = useAgentStore((s) => s.performance);

  useEffect(() => { loadList(); }, []);
  const loadList = async () => {
    setLoading(true);
    try { setModels(await modelService.listLocalModels()); }
    catch (e: any) { Alert.alert('Ошибка', e.message); }
    finally { setLoading(false); }
  };
  const handleSelect = async (model: ModelInfo) => {
    setLoadingModel(model.id);
    try { await modelService.loadModel(model.path, performance); Alert.alert('Готово', `Модель «${model.name}» загружена`); onModelLoaded(); }
    catch (e: any) { Alert.alert('Ошибка загрузки', e.message); }
    finally { setLoadingModel(null); }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}><TouchableOpacity onPress={onBack}><Text style={styles.back}>← Назад</Text></TouchableOpacity><Text style={styles.title}>Модели</Text></View>
      <Text style={styles.hint}>Положи .gguf файлы в папку DocumentDirectory/models</Text>
      {loading ? <ActivityIndicator size="large" color="#6a9eff" style={{ marginTop: 40 }} /> : models.length === 0 ? (
        <View style={styles.empty}><Text style={styles.emptyText}>Модели не найдены</Text><Text style={styles.emptyHint}>Скачай GGUF (например Qwen2.5-Coder-7B Q4_K_M) и положи в папку models</Text></View>
      ) : (
        <FlatList data={models} keyExtractor={(item) => item.id} contentContainerStyle={{ padding: 16 }} renderItem={({ item }) => (
          <TouchableOpacity style={styles.card} onPress={() => handleSelect(item)} disabled={!!loadingModel}>
            <View style={{ flex: 1 }}><Text style={styles.modelName}>{item.name}</Text><Text style={styles.modelSize}>{item.sizeMB} MB</Text></View>
            {loadingModel === item.id ? <ActivityIndicator color="#6a9eff" /> : <Text style={styles.loadText}>Загрузить</Text>}
          </TouchableOpacity>
        )} />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b0b0b' },
  header: { flexDirection: 'row', alignItems: 'center', padding: 16, gap: 16 }, back: { color: '#6a9eff', fontSize: 16 }, title: { color: '#fff', fontSize: 20, fontWeight: '600' },
  hint: { color: '#666', fontSize: 13, paddingHorizontal: 16, marginBottom: 8 }, empty: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 32 }, emptyText: { color: '#fff', fontSize: 18, marginBottom: 8 }, emptyHint: { color: '#777', textAlign: 'center', lineHeight: 20 },
  card: { backgroundColor: '#1a1a1a', borderRadius: 14, padding: 16, marginBottom: 10, flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: '#2a2a2a' }, modelName: { color: '#fff', fontSize: 16, fontWeight: '500' }, modelSize: { color: '#888', fontSize: 13, marginTop: 4 }, loadText: { color: '#6a9eff', fontSize: 14 },
});
