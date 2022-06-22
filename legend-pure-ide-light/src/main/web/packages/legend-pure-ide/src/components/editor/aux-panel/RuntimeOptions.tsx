/**
 * Copyright (c) 2020-present, Goldman Sachs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { observer } from 'mobx-react-lite';
import { useEditorStore } from '../../../stores/EditorStore';
import { useState } from "react";

export const RuntimeOptions= observer(() => {
  const editorStore = useEditorStore();
  const [debugPlanOption, setDebugPlanOption] = useState(false);
  const [execPlanOption, setExecPlanOption] = useState(false);
  const [planLocalOption, setPlanLocalOption] = useState(false);
  const [showLocalPlanOption, setShowLocalPlanOption] = useState(false);

  const handleDebugOptionChange = () => {
    setDebugPlanOption(!debugPlanOption);
    editorStore.setDebugPlatformCodeGen(debugPlanOption);
  }
  const handleExecPlanOptionChange = () => {
    setExecPlanOption(!execPlanOption);
    editorStore.setExecPlan(execPlanOption);
  }
  const handlePlanLocalOptionChange = () => {
    setPlanLocalOption(!planLocalOption);
    editorStore.setPlanLocal(planLocalOption);
  }
  const handleShowLocalPlanOptionChange = () => {
    setShowLocalPlanOption(!showLocalPlanOption);
    editorStore.setShowLocalPlan(showLocalPlanOption);
  }
  const Checkbox = (obj: { label : string; value: boolean, padding?:number; onChange : () => void}) => {
  let id="runtimeoptions-panel-item-".concat(obj.label);
      let style = obj.padding == undefined ? {paddingLeft : 20, paddingTop: 5, paddingBottom: 5} : {paddingLeft : obj.padding, paddingTop: 5, paddingBottom: 5}; 
  return (
	<div className={id} style={style}>
      <label>
        <input type="checkbox" checked={obj.value} onChange={obj.onChange}/>
        {obj.label}
      </label>
      </div>
  )
  };

  const checkboxes = [
    { label : 'Debug Platform Code Gen', value:editorStore.debugPlatformCodeGen, onChange : handleDebugOptionChange},
    { label : 'Exec Plan', value : editorStore.execPlan, onChange : handleExecPlanOptionChange},
  ];

  return (
    <div className="runtimeoptions-panel">
	  <ul className='runtimeoptions-list'>
	  <Checkbox label={"Calculate and execute plans on Engine as opposed to executing functions on Engine"} value={editorStore.execPlan} onChange={handleExecPlanOptionChange}/>
	  <Checkbox label={"Calculate plans in the IDE and then execute on Engine as opposed to calculating and executing on Engine"} value={editorStore.planLocal} onChange={handlePlanLocalOptionChange}/>
	  <Checkbox label={"... and debug platform code generation"} padding={40} value={editorStore.debugPlatformCodeGen} onChange={handleDebugOptionChange}/>
	  <Checkbox label={"... and print the plan calculated"} padding={40} value={editorStore.showLocalPlan} onChange={handleShowLocalPlanOptionChange}/>
    </ul>
    </div>
  )
})


