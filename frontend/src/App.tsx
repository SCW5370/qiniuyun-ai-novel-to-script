import { DirectorCommandRoom } from "./features/workbench/DirectorCommandRoom";
import { ProductHome } from "./features/home/ProductHome";

function App() {
  return (
    <>
      <ProductHome />
      <section id="workbench" className="workbench-section" aria-label="制作工作台">
        <DirectorCommandRoom />
      </section>
    </>
  );
}

export default App;
