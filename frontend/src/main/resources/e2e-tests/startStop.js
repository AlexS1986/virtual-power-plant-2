import { Selector } from 'testcafe';

fixture`Main Page`
    .page`http://192.168.49.2:30408`;

test('U1-U3: A battery should be able to be started and stopped', async t => {
    const deviceExists = Selector(".bh-batteryWidget").exists
    
    await t.expect(deviceExists).notOk("No devices should exist before add button is clicked.")
    
    // start
    await t
        .click('#addButton')
        .wait(2000);
    await t.expect(Selector(".bh-batteryWidget").count).eql(1,"Exactly one device should exist after the add button has been clicked.")
    const stopButtons = await Selector(".stopButton");
    await t.expect(stopButtons.count).eql(1,"Exactly one stop button should be displayed after the add button has been clicked.")

    // stop
    await t 
        .click(stopButtons.nth(0)).wait(3000)
    await t.expect(Selector(".bh-batteryWidget").count).eql(0,"No devices should be displayed 3s after stop has been clicked")
});


