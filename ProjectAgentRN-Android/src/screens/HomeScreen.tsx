import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity, SafeAreaView, StatusBar } from 'react-native';
import { useAgentStore } from '../store/useAgentStore';

interface Props {
  onSelectModel: () => void;
  onSelectProject: () => void;
  onOpenSettings: () => void;
}

export default function HomeScreen({ onSelectModel, onSelectProject, onOpenSettings }: Props) {
  const { currentProject, isRunning } = useAgentStore();

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#0b0b0b" />
      <View style={styles.header}>
        <Text style={styles.title}>ProjectAgent</Text>
        <Text style={styles.subtitle}>Локальный AI-агент для проектов</Text>
      </View>
      <View style={styles.content}>
        <TouchableOpacity style={styles.button} onPress={onSelectModel} activeOpacity={0.8}>
          <Text style={styles.buttonText}>1. Выбрать модель</Text>
          <Text style={styles.buttonHint}>GGUF файлы из папки models</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.button, styles.buttonSecondary]} onPress={onSelectProject} activeOpacity={0.8}>
          <Text style={styles.buttonText}>2. Загрузить ZIP проекта</Text>
          <Text style={styles.buttonHint}>
            {currentProject ? `Текущий: ${currentProject.name} (${currentProject.fileCount} файлов)` : 'Распакует и подготовит песочницу'}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.button, styles.buttonAccent, (!currentProject || isRunning) && styles.buttonDisabled]}
          onPress={onSelectProject}
          disabled={!currentProject || isRunning}
          activeOpacity={0.8}
        >
          <Text style={styles.buttonText}>{isRunning ? 'Агент работает...' : '3. Запустить агента'}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.settingsButton} onPress={onOpenSettings}>
          <Text style={styles.settingsText}>Настройки производительности</Text>
        </TouchableOpacity>
      </View>
      <View style={styles.footer}>
        <Text style={styles.footerText}>Realme GT Neo 5 • Snapdragon 8+ Gen 1 • 16 GB</Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b0b0b' },
  header: { paddingTop: 32, paddingHorizontal: 24, paddingBottom: 16 },
  title: { fontSize: 28, fontWeight: '700', color: '#ffffff' },
  subtitle: { fontSize: 15, color: '#888', marginTop: 4 },
  content: { flex: 1, paddingHorizontal: 20, paddingTop: 24, gap: 14 },
  button: { backgroundColor: '#1a1a1a', borderRadius: 16, padding: 20, borderWidth: 1, borderColor: '#2a2a2a' },
  buttonSecondary: { backgroundColor: '#141414' },
  buttonAccent: { backgroundColor: '#1f3a5f', borderColor: '#2d5a8f' },
  buttonDisabled: { opacity: 0.45 },
  buttonText: { color: '#ffffff', fontSize: 17, fontWeight: '600' },
  buttonHint: { color: '#777', fontSize: 13, marginTop: 6 },
  settingsButton: { marginTop: 20, alignItems: 'center', padding: 12 },
  settingsText: { color: '#6a9eff', fontSize: 15 },
  footer: { padding: 16, alignItems: 'center' },
  footerText: { color: '#444', fontSize: 12 },
});
