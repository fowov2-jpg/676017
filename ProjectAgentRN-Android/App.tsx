import React, { useState } from 'react';
import { StatusBar } from 'react-native';
import HomeScreen from './src/screens/HomeScreen';
import ModelScreen from './src/screens/ModelScreen';
import ProjectScreen from './src/screens/ProjectScreen';
import AgentScreen from './src/screens/AgentScreen';

type Screen = 'home' | 'model' | 'project' | 'agent';

export default function App() {
  const [screen, setScreen] = useState<Screen>('home');

  return (
    <>
      <StatusBar barStyle="light-content" backgroundColor="#0b0b0b" />

      {screen === 'home' && (
        <HomeScreen
          onSelectModel={() => setScreen('model')}
          onSelectProject={() => setScreen('project')}
          onOpenSettings={() => {
            // TODO: экран настроек
          }}
        />
      )}

      {screen === 'model' && (
        <ModelScreen
          onBack={() => setScreen('home')}
          onModelLoaded={() => setScreen('home')}
        />
      )}

      {screen === 'project' && (
        <ProjectScreen
          onBack={() => setScreen('home')}
          onStartAgent={() => setScreen('agent')}
        />
      )}

      {screen === 'agent' && <AgentScreen onBack={() => setScreen('home')} />}
    </>
  );
}
