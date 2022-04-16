import { Selector } from 'testcafe';

fixture`Main Page`
    .page`http://192.168.49.2:30408`;
    //.page`https://devexpress.github.io/testcafe/example`;

test('U1: A battery should be able to be started', async t => {
    const deviceExists = Selector(".bh-batteryWidget").exists
    await t.expect(deviceExists).notOk("No devices should exist before add button is clicked.")
    
    await t
        .click('#addButton')
        .wait(2000);

    
    const deviceCount = Selector(".bh-batteryWidget").count;

    await t.expect(deviceCount).eql(1,"Exactly one device should exist after the add button has been clicked.")
        
});


