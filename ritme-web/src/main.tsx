import { render } from 'preact';
import './styles.css';

function Boot() {
  return <main class="screen"><h1>Ritme</h1></main>;
}

render(<Boot />, document.getElementById('app')!);
